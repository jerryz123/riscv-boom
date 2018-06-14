//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Issue Logic
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.Str
import boom.common._
import boom.exu.FUConstants._
import scala.collection.mutable.ArrayBuffer

//-------------------------------------------------------------
//-------------------------------------------------------------

case class IssueParams(
   issueWidth: Int = 1,
   numEntries: Int = 8,
   iqType: BigInt
)

class WakeupPdst(implicit p: Parameters) extends BoomBundle()(p)
{
   val pdst = UInt(width=PREG_SZ.W)
   val eidx = UInt(width=VL_SZ.W)
}

class IssueUnitIO(
   val issue_width: Int,
   val num_wakeup_ports: Int)
   (implicit p: Parameters) extends BoomBundle()(p)
{
   val dis_valids     = Input(Vec(DISPATCH_WIDTH, Bool()))
   val dis_uops       = Input(Vec(DISPATCH_WIDTH, new MicroOp()))
   val dis_readys     = Output(Vec(DISPATCH_WIDTH, Bool()))

   val iss_valids     = Output(Vec(issue_width, Bool()))
   val iss_uops       = Output(Vec(issue_width, new MicroOp()))
   val wakeup_pdsts   = Flipped(Vec(num_wakeup_ports, Valid(new WakeupPdst())))


   // tell the issue unit what each execution pipeline has in terms of functional units
   val fu_types       = Input(Vec(issue_width, Bits(width=FUC_SZ.W)))

   val brinfo         = Input(new BrResolutionInfo())
   val flush_pipeline = Input(Bool())

   val event_empty    = Output(Bool()) // used by HPM events; is the issue unit empty?

   val tsc_reg        = Input(UInt(width=xLen.W))
   val vl             = Input(UInt(width=VL_SZ.W))
   val lsu_ldq_head_eidx   = Input(UInt())
   val lsu_ldq_head        = Input(UInt())
   val lsu_stq_head_eidx   = Input(UInt())
   val lsu_stq_head        = Input(UInt())
   val commit_load_at_rob_head = Input(Bool())
   val commit_store_at_rob_head = Input(Bool())
}

abstract class IssueUnit(
   val num_issue_slots: Int,
   val issue_width: Int,
   containsVec: Boolean,
   num_wakeup_ports: Int,
   val iqType: BigInt)
   (implicit p: Parameters)
   extends BoomModule()(p)
{
   val io = IO(new IssueUnitIO(issue_width, num_wakeup_ports))

   //-------------------------------------------------------------
   // Set up the dispatch uops
   // special case "storing" 2 uops within one issue slot.

   val dis_uops = Array.fill(DISPATCH_WIDTH) {Wire(new MicroOp())}
//   val vec_uops = Array.fill(issue_width) {Wire(new MicroOp())}
   for (w <- 0 until DISPATCH_WIDTH)
   {
      dis_uops(w) := io.dis_uops(w)
      dis_uops(w).iw_state := s_valid_1
      when ((dis_uops(w).uopc === uopSTA && dis_uops(w).lrs2_rtype === RT_FIX) || dis_uops(w).uopc === uopAMO_AG)
      {
         dis_uops(w).iw_state := s_valid_2
      }
   }

   //-------------------------------------------------------------
   // Issue Table

   val slots = for (i <- 0 until num_issue_slots) yield { val slot = Module(new IssueSlot(num_wakeup_ports, containsVec)); slot; }
   val issue_slots = VecInit(slots.map(_.io))
   for (i <- 0 until num_issue_slots) yield {
      issue_slots(i).lsu_ldq_head_eidx := io.lsu_ldq_head_eidx
      issue_slots(i).lsu_ldq_head      := io.lsu_ldq_head
      issue_slots(i).lsu_stq_head_eidx := io.lsu_stq_head_eidx
      issue_slots(i).lsu_stq_head      := io.lsu_stq_head
      issue_slots(i).commit_load_at_rob_head := io.commit_load_at_rob_head
      issue_slots(i).commit_store_at_rob_head := io.commit_store_at_rob_head
      issue_slots(i).vl := io.vl
   }

   io.event_empty := !(issue_slots.map(s => s.valid).reduce(_|_))

   //-------------------------------------------------------------

   assert (PopCount(issue_slots.map(s => s.grant)) <= issue_width.U, "Issue window giving out too many grants.")

   //-------------------------------------------------------------

   if (O3PIPEVIEW_PRINTF)
   {
      for (i <- 0 until issue_width)
      {
         // only print stores once!
         when (io.iss_valids(i) && io.iss_uops(i).uopc =/= uopSTD)
         {
            printf("%d; O3PipeView:issue: %d\n",
               io.iss_uops(i).debug_events.fetch_seq,
               io.tsc_reg)
         }
      }
   }

   if (DEBUG_PRINTF)
   {
      val typ_str = if (iqType == IQT_INT.litValue) "int"
                    else if (iqType == IQT_MEM.litValue) "mem"
                    else if (iqType == IQT_FP.litValue) " fp"
                    else if (iqType == IQT_VEC.litValue) "vec"
                    else "unknown"
      for (i <- 0 until num_issue_slots)
      {
         printf("  " + typ_str + "_issue_slot[%d](%c)(Req:%c):wen=%c P:(%c,%c,%c) OP:(%d,%d,%d) PDST:%d %c [[DASM(%x)]" +
               " 0x%x: %d] ri:%d bm=%d imm=0x%x eidx=%d\n"
            , UInt(i, log2Up(num_issue_slots))
            , Mux(issue_slots(i).valid, Str("V"), Str("-"))
            , Mux(issue_slots(i).request, Str("R"), Str("-"))
            , Mux(issue_slots(i).in_uop.valid, Str("W"),  Str(" "))
            , Mux(issue_slots(i).debug.p1, Str("!"), Str(" "))
            , Mux(issue_slots(i).debug.p2, Str("!"), Str(" "))
            , Mux(issue_slots(i).debug.p3, Str("!"), Str(" "))
            , issue_slots(i).uop.pop1
            , issue_slots(i).uop.pop2
            , issue_slots(i).uop.pop3
            , issue_slots(i).uop.pdst
            , Mux(issue_slots(i).uop.dst_rtype === RT_FIX, Str("X"),
              Mux(issue_slots(i).uop.dst_rtype === RT_X, Str("-"),
              Mux(issue_slots(i).uop.dst_rtype === RT_FLT, Str("f"),
              Mux(issue_slots(i).uop.dst_rtype === RT_VEC, Str("V"),
              Mux(issue_slots(i).uop.dst_rtype === RT_PAS, Str("C"), Str("?"))))))
            , issue_slots(i).uop.inst
            , issue_slots(i).uop.pc(31,0)
            , issue_slots(i).uop.uopc
            , issue_slots(i).uop.rob_idx
            , issue_slots(i).uop.br_mask
            , issue_slots(i).uop.imm_packed
            , issue_slots(i).uop.eidx
         )
      }
      printf("-----------------------------------------------------------------------------------------\n")
   }

   override val compileOptions = chisel3.core.ExplicitCompileOptions.NotStrict.copy(explicitInvalidate = true)
}

class IssueUnits(num_wakeup_ports: Int)(implicit val p: Parameters)
   extends HasBoomCoreParameters
   with IndexedSeq[IssueUnit]
{
   //*******************************
   // Instantiate the IssueUnits

   private val iss_units = ArrayBuffer[IssueUnit]()

   //*******************************
   // Act like a collection

   def length = iss_units.length

   def apply(n: Int): IssueUnit = iss_units(n)

   //*******************************
   // Construct.

   require (enableAgePriorityIssue) // unordered is currently unsupported.

//      issue_Units =issueConfigs colect {if iqType=....)
   iss_units += Module(new IssueUnitCollasping(issueParams.find(_.iqType == IQT_MEM.litValue).get, true, num_wakeup_ports))
   iss_units += Module(new IssueUnitCollasping(issueParams.find(_.iqType == IQT_INT.litValue).get, false, num_wakeup_ports))
}

