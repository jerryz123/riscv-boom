//******************************************************************************
// Copyright (c) 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Floating Point Datapath Pipeline
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Hankun Zhao

// The vector issue window, regfile, and arithmetic units are all handled here.

package boom.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket
import freechips.rocketchip.tile
import freechips.rocketchip.util._
import boom.exu.FUConstants._
import boom.common._
import boom.util._

class VecPipeline(implicit p: Parameters) extends BoomModule()(p) with tile.HasFPUParameters
with freechips.rocketchip.rocket.constants.VecCfgConstants
{
  val vecIssueParams = issueParams.find(_.iqType == IQT_VEC.litValue).get
  val num_ll_ports = 1 // TODO_VEC: add ll wb ports
  val num_wakeup_ports = vecIssueParams.issueWidth
  val vec_preg_sz = log2Up(numVecPhysRegs)

  val io = new Bundle
  {
     val brinfo         = Input(new BrResolutionInfo())
     val flush_pipeline = Input(Bool())
     val fcsr_rm = Input(UInt(width=freechips.rocketchip.tile.FPConstants.RM_SZ.W))
     // TODO: Add inputs from rocket CSRFile

     val dis_valids     = Input(Vec(DISPATCH_WIDTH, Bool()))
     val dis_uops       = Input(Vec(DISPATCH_WIDTH, new MicroOp()))
     val dis_readys     = Output(Vec(DISPATCH_WIDTH, Bool()))

     val ll_wport       = Flipped(Decoupled(new ExeUnitResp(128))) // from memory unit
     val tosdq          = new DecoupledIO(new MicroOpWithData(128))
//     val fromint        = Flipped(Decoupled(new FuncUnitReq(fLen+1))) // from integer RF
     val fromfp         = Flipped(Decoupled(new ExeUnitResp(xLen))) // from fp RF.
//     val toint          = Decoupled(new ExeUnitResp(xLen))

     val wakeups        = Vec(num_wakeup_ports, Valid(new ExeUnitResp(128)))
     val wb_valids      = Input(Vec(num_wakeup_ports, Bool()))
     val vb_pdsts       = Input(Vec(num_wakeup_ports, UInt(width=vec_preg_sz.W)))

     val debug_tsc_reg  = Input(UInt(width=128.W))
     val vl             = Input(UInt(width=VL_SZ.W))

     val lsu_stq_head      = Input(UInt())
     val commit_load_at_rob_head = Input(Bool())
     val commit_store_at_rob_head = Input(Bool())
  }

   val exe_units = new boom.exu.ExecutionUnits(vec = true)
   val issue_unit = Module(new IssueUnitCollasping(issueParams.find(_.iqType == IQT_VEC.litValue).get, true,
      true,
      num_wakeup_ports)) // TODO_VEC: Make this a VectorIssueUnit
   val vregfile = Module(new RegisterFileBehavorial(numVecRegFileRows,
      exe_units.withFilter(_.uses_iss_unit).map(e=>e.num_rf_read_ports).sum,
      exe_units.withFilter(_.uses_iss_unit).map(e=>e.num_rf_write_ports).sum, // TODO_VEC: Subtract write ports to IRF, FRF
      128,
      true,
      exe_units.bypassable_write_port_mask
   ))
   assert(exe_units.num_total_bypass_ports == 0, "Vector pipeline does not support bypassing")
   val vregister_read = Module(new RegisterRead(
      issue_unit.issue_width,
      exe_units.withFilter(_.uses_iss_unit).map(_.supportedFuncUnits),
      exe_units.withFilter(_.uses_iss_unit).map(_.num_rf_read_ports).sum,
      exe_units.withFilter(_.uses_iss_unit).map(_.num_rf_read_ports),
      exe_units.num_total_bypass_ports,
      true,
      128))

   require (exe_units.withFilter(_.uses_iss_unit).map(x=>x).length == issue_unit.issue_width)
   require (exe_units.map(_.num_rf_write_ports).sum == num_wakeup_ports)
   require (exe_units.withFilter(_.uses_iss_unit).map(e=>
      e.num_rf_write_ports).sum == num_wakeup_ports)


   val tosdq = Module(new Queue(new MicroOpWithData(128), 4))

   // Todo_vec add checking for num write ports and number of functional units which use the issue unit

   val iss_valids = Wire(Vec(exe_units.withFilter(_.uses_iss_unit).map(x=>x).length, Bool()))
   val iss_uops   = Wire(Vec(exe_units.withFilter(_.uses_iss_unit).map(x=>x).length, new MicroOp()))

   issue_unit.io.tsc_reg := io.debug_tsc_reg
   issue_unit.io.brinfo := io.brinfo
   issue_unit.io.flush_pipeline := io.flush_pipeline
   issue_unit.io.vl := io.vl

   issue_unit.io.stdata_ready      := tosdq.io.count < UInt(2)
   issue_unit.io.lsu_stq_head      := io.lsu_stq_head
   issue_unit.io.commit_load_at_rob_head := io.commit_load_at_rob_head
   issue_unit.io.commit_store_at_rob_head := io.commit_store_at_rob_head
   issue_unit.io.fromfp_valid      := io.fromfp.valid
   issue_unit.io.fromfp_paddr      := io.fromfp.bits.uop.pdst
   issue_unit.io.fromfp_data       := io.fromfp.bits.data
   require (exe_units.num_total_bypass_ports == 0)


   //-------------------------------------------------------------
   // **** Dispatch Stage ****
   //-------------------------------------------------------------

   // Input (Dispatch)
   for (w <- 0 until DISPATCH_WIDTH)
   {
      issue_unit.io.dis_valids(w) := io.dis_valids(w) && io.dis_uops(w).iqtype === issue_unit.iqType.U
      issue_unit.io.dis_uops(w) := io.dis_uops(w)

      when (io.dis_uops(w).uopc === uopVST && io.dis_uops(w).lrs3_rtype === RT_VEC) {
         issue_unit.io.dis_valids(w) := io.dis_valids(w)
         issue_unit.io.dis_uops(w).uopc := uopVST
         issue_unit.io.dis_uops(w).fu_code := FUConstants.FU_VALU
         issue_unit.io.dis_uops(w).lrs1_rtype := RT_X
         issue_unit.io.dis_uops(w).prs1_busy := false.B
      }
      when (io.dis_uops(w).lrs1_rtype === RT_FLT) {
         issue_unit.io.dis_uops(w).prs1_busy := true.B
      }
      when (io.dis_uops(w).lrs2_rtype === RT_FLT) {
         issue_unit.io.dis_uops(w).prs2_busy := true.B
      }
      when (io.dis_uops(w).lrs3_rtype === RT_FLT) {
         issue_unit.io.dis_uops(w).prs3_busy := true.B
      }

   }
   io.dis_readys := issue_unit.io.dis_readys

   //-------------------------------------------------------------
   // **** Issue Stage ****
   //-------------------------------------------------------------

   val ll_wb_block_issue = Wire(Bool())

   // Output (Issue)
   for (i <- 0 until issue_unit.issue_width)
   {
      iss_valids(i) := issue_unit.io.iss_valids(i)
      iss_uops(i) := issue_unit.io.iss_uops(i)

      issue_unit.io.fu_types(i) := exe_units(i).io.fu_types & ~Mux(ll_wb_block_issue, FU_VALU | FU_VFPU, UInt(0)) 

      require (exe_units(i).uses_iss_unit)
   }

   // Wakeup
   for ((writeback, issue_wakeup) <- io.wakeups zip issue_unit.io.wakeup_pdsts)
   {
      when (writeback.valid)
      {
         // printf("%d Vec wakeup writeback valid received\n", io.debug_tsc_reg)
      }
      issue_wakeup.valid := writeback.valid
      issue_wakeup.bits.pdst  := writeback.bits.uop.pdst
      issue_wakeup.bits.eidx  := writeback.bits.uop.eidx + writeback.bits.uop.rate // TODO_vec: This is a lot of adders
   }


   //-------------------------------------------------------------
   // **** Register Read Stage ****
   //-------------------------------------------------------------

   // Register Read <- Issue (rrd <- iss)
   vregister_read.io.rf_read_ports <> vregfile.io.read_ports

   vregister_read.io.iss_valids <> iss_valids
   vregister_read.io.iss_uops := iss_uops

   vregister_read.io.brinfo := io.brinfo
   vregister_read.io.kill := io.flush_pipeline

   //-------------------------------------------------------------
   // **** Execute Stage ****
   //-------------------------------------------------------------

   exe_units.map(_.io.brinfo := io.brinfo)
   exe_units.map(_.io.com_exception := io.flush_pipeline)

   for ((ex,w) <- exe_units.withFilter(_.uses_iss_unit).map(x=>x).zipWithIndex)
   {
      ex.io.req <> vregister_read.io.exe_reqs(w)
      require (!ex.isBypassable)

      require (w == 0)
      if (w == 0) {
         when (vregister_read.io.exe_reqs(w).bits.uop.uopc === uopVST) {
            ex.io.req.valid := false.B
         }


         val vew = vregister_read.io.exe_reqs(w).bits.uop.rs3_vew
         val eidx = vregister_read.io.exe_reqs(w).bits.uop.eidx
         val shiftn = Cat((eidx <<
            MuxLookup(vregister_read.io.exe_reqs(w).bits.uop.rs3_vew, VEW_8, Array(
               VEW_8  -> UInt(0),
               VEW_16 -> UInt(1),
               VEW_32 -> UInt(2),
               VEW_64 -> UInt(3)))) & "b1111".U, UInt(0, width=3))

         tosdq.io.enq.valid     := vregister_read.io.exe_reqs(w).bits.uop.uopc === uopVST
         tosdq.io.enq.bits.uop  := vregister_read.io.exe_reqs(w).bits.uop
         tosdq.io.enq.bits.data := vregister_read.io.exe_reqs(w).bits.rs3_data >> shiftn
         io.tosdq               <> tosdq.io.deq
         tosdq.io.deq.ready     := io.tosdq.ready
         io.tosdq.valid         := tosdq.io.deq.valid
         io.tosdq.bits.uop      := tosdq.io.deq.bits.uop
         io.tosdq.bits.data     := tosdq.io.deq.bits.data
      }
   }
   require (exe_units.num_total_bypass_ports == 0)


   //-------------------------------------------------------------
   // **** Writeback Stage ****
   //-------------------------------------------------------------

   val ll_wb = Module(new Queue(new ExeUnitResp(128), 8)) // TODO_Vec: Tune these
   ll_wb_block_issue := ll_wb.io.count >= UInt(4)
   ll_wb.io.enq <> io.ll_wport
   assert (ll_wb.io.enq.ready, "We do not support backpressure on this queue")
   when   (io.ll_wport.valid) { assert(io.ll_wport.bits.uop.ctrl.rf_wen && io.ll_wport.bits.uop.dst_rtype === RT_VEC) }


   var w_cnt = 0 // TODO_Vec: check if this should be 1 or 0 for vec?
   var vec_eu_wb = false.B
   for (eu <- exe_units)
   {
      eu.io.debug_tsc_reg := io.debug_tsc_reg
      for (wbresp <- eu.io.resp)
      {
         val valid_write = wbresp.valid && wbresp.bits.uop.ctrl.rf_wen
         vregfile.io.write_ports(w_cnt).valid := valid_write


         vec_eu_wb = valid_write | vec_eu_wb
         vregfile.io.write_ports(w_cnt).bits.addr := CalcVecRegAddr(
            wbresp.bits.uop.rd_vew,
            wbresp.bits.uop.eidx,
            wbresp.bits.uop.pdst,
            numVecPhysRegs)
         vregfile.io.write_ports(w_cnt).bits.data := wbresp.bits.data
         vregfile.io.write_ports(w_cnt).bits.mask := wbresp.bits.mask
         vregfile.io.write_ports(w_cnt).bits.eidx := wbresp.bits.uop.eidx
         vregfile.io.write_ports(w_cnt).bits.rd_vew := wbresp.bits.uop.rd_vew
         wbresp.ready := vregfile.io.write_ports(w_cnt).ready


         assert (!(wbresp.valid &&
            !wbresp.bits.uop.ctrl.rf_wen &&
            wbresp.bits.uop.dst_rtype === RT_VEC),
            "[vecpipeline] An VEC writeback is being attempted with rf_wen disabled.")

         assert (!(wbresp.valid &&
            wbresp.bits.uop.ctrl.rf_wen &&
            wbresp.bits.uop.dst_rtype =/= RT_VEC),
            "[vecpipeline] A writeback is being attempted to the VEC RF with dst != VEC type.")

         w_cnt += 1
      }
   }
   ll_wb.io.deq.ready := false.B
   when (!vec_eu_wb) {
      vregfile.io.write_ports(0) <> WritePort(ll_wb.io.deq, log2Ceil(numVecRegFileRows), 128, true, numVecPhysRegs)
      ll_wb.io.deq.ready := true.B
   }
   require (w_cnt == vregfile.io.write_ports.length)

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Commit Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   w_cnt = 0
   var vec_eu_wakeup = false.B
   for (eu <- exe_units)
   {
      for (exe_resp <- eu.io.resp)
      {
         val wb_uop = exe_resp.bits.uop
         assert (!exe_resp.bits.writesToIRF, "Why would this write to IRF?")
         if (!exe_resp.bits.writesToIRF && !eu.has_ifpu) { // TODO_Vec: What is this for?
            val wport        = io.wakeups(w_cnt)
            val wakeup_valid = exe_resp.valid && wb_uop.dst_rtype === RT_VEC
            wport.valid     := wakeup_valid
            vec_eu_wakeup    = wakeup_valid | vec_eu_wakeup
            wport.bits      := exe_resp.bits

            w_cnt += 1

            assert(!(exe_resp.valid && wb_uop.is_store))
            assert(!(exe_resp.valid && wb_uop.is_load))
            assert(!(exe_resp.valid && wb_uop.is_amo))
         }
      }
   }
   when (!vec_eu_wakeup) {
      io.wakeups(0).valid := ll_wb.io.deq.valid && ll_wb.io.deq.ready
      io.wakeups(0).bits  := ll_wb.io.deq.bits
   }


   exe_units.map(_.io.fcsr_rm := io.fcsr_rm)

   //-------------------------------------------------------------
   // **** Flush Pipeline ****
   //-------------------------------------------------------------
   // flush on exceptions, miniexeptions, and after some special instructions

   for (w <- 0 until exe_units.length)
   {
      exe_units(w).io.req.bits.kill := io.flush_pipeline
   }

   val vec_string = exe_units.toString
   override def toString: String =
      vregfile.toString +
   "\n   Num Wakeup Ports      : " + num_wakeup_ports +
   "\n   Num Bypass Ports      : " + exe_units.num_total_bypass_ports + "\n"
}
