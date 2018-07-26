//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Datapath Register File
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2013 May 1


package boom.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import scala.collection.mutable.ArrayBuffer
import boom.common._
import boom.util._

class RegisterFileReadPortIO(addr_width: Int, data_width: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val addr = Input(UInt(addr_width.W))
   val data = Output(UInt(data_width.W))
   override def cloneType = new RegisterFileReadPortIO(addr_width, data_width)(p).asInstanceOf[this.type]
}

class RegisterFileWritePort(addr_width: Int, data_width: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val addr = UInt(width = addr_width.W)
   val data = UInt(width = data_width.W)
   val eidx = UInt(width = VL_SZ.W)
   val mask = UInt(width = (data_width / 8).W)
   val rd_vew = UInt(width = VEW_SZ.W)
   override def cloneType = new RegisterFileWritePort(addr_width, data_width)(p).asInstanceOf[this.type]
}


// utility function to turn ExeUnitResps to match the regfile's WritePort I/Os.
object WritePort
{
   def apply(enq: DecoupledIO[ExeUnitResp], addr_width: Int, data_width: Int, isVector: Boolean, numVecPhysRegs:Int)
   (implicit p: Parameters): DecoupledIO[RegisterFileWritePort] =
   {
      val wport = Wire(Decoupled(new RegisterFileWritePort(addr_width, data_width)))
      wport.valid := enq.valid
      if (isVector) {
         wport.bits.addr := CalcVecRegAddr(
            enq.bits.uop.rd_vew,
            enq.bits.uop.eidx,
            enq.bits.uop.pdst,
            numVecPhysRegs)
      } else {
         wport.bits.addr := enq.bits.uop.pdst
      }
      wport.bits.data := enq.bits.data
      wport.bits.mask := enq.bits.mask
      wport.bits.eidx := enq.bits.uop.eidx
      wport.bits.rd_vew := enq.bits.uop.rd_vew
      enq.ready := wport.ready

      wport
   }
}


abstract class RegisterFile(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   isVector: Boolean,
   bypassable_array: Seq[Boolean]) // which write ports can be bypassed to the read ports?
   (implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new BoomBundle()(p)
   {
      val read_ports = Vec(num_read_ports, new RegisterFileReadPortIO(log2Ceil(num_registers), register_width))
      val write_ports = Vec(num_write_ports, Decoupled(new RegisterFileWritePort(log2Ceil(num_registers), register_width))).flip
   })

   private val rf_cost = (num_read_ports+num_write_ports)*(num_read_ports+2*num_write_ports)
   private val type_str = if (register_width == fLen+1) "Floating Point" else "Integer"
   override def toString: String =
      "\n   ==" + type_str + " Regfile==" +
      "\n   Num RF Read Ports     : " + num_read_ports +
      "\n   Num RF Write Ports    : " + num_write_ports +
      "\n   RF Cost (R+W)*(R+2W)  : " + rf_cost
}

// A behavorial model of a Register File. You will likely want to blackbox this for more than modest port counts.
class RegisterFileBehavorial(
   num_registers: Int,
   num_read_ports: Int,
   num_write_ports: Int,
   register_width: Int,
   isVector: Boolean,
   bypassable_array: Seq[Boolean])
   (implicit p: Parameters)
      extends RegisterFile(num_registers, num_read_ports, num_write_ports, register_width, isVector, bypassable_array)
with freechips.rocketchip.rocket.constants.VecCfgConstants
{
   // --------------------------------------------------------------

   val regfile = Mem(num_registers, UInt(width=register_width.W))

   // --------------------------------------------------------------
   // Read ports.

   val read_data = Wire(Vec(num_read_ports, UInt(width = register_width.W)))

   // Register the read port addresses to give a full cycle to the RegisterRead Stage (if desired).
   val read_addrs =
      if (regreadLatency == 0) {
         io.read_ports map {_.addr}
      } else {
         require (regreadLatency == 1)
         io.read_ports.map(p => RegNext(p.addr))
      }

   for (i <- 0 until num_read_ports)
   {
      read_data(i) :=
         Mux(read_addrs(i) === UInt(0),
            UInt(0),
            regfile(read_addrs(i)))
   }


   // --------------------------------------------------------------
   // Bypass out of the ALU's write ports.
   // We are assuming we cannot bypass a writer to a reader within the regfile memory
   // for a write that occurs at the end of cycle S1 and a read that returns data on cycle S1.
   // But since these bypasses are expensive, and not all write ports need to bypass their data,
   // only perform the w->r bypass on a select number of write ports.

   require (bypassable_array.length == io.write_ports.length)

   if (bypassable_array.reduce(_||_))
   {
      val bypassable_wports = ArrayBuffer[DecoupledIO[RegisterFileWritePort]]()
      io.write_ports zip bypassable_array map { case (wport, b) => if (b) { bypassable_wports += wport} }

      for (i <- 0 until num_read_ports)
      {
         val bypass_ens = bypassable_wports.map(x => x.valid &&
                                                  x.bits.addr =/= UInt(0) &&
                                                  x.bits.addr === read_addrs(i))

         val bypass_data = Mux1H(Vec(bypass_ens), Vec(bypassable_wports.map(_.bits.data)))

         io.read_ports(i).data := Mux(bypass_ens.reduce(_|_), bypass_data, read_data(i))
      }
   }
   else
   {
      for (i <- 0 until num_read_ports)
      {
         io.read_ports(i).data := read_data(i)
      }
   }
   def toBytes(wdata:UInt, bitmask:UInt, eidx:UInt, rd_vew:UInt): (UInt, UInt) = {
      val mask = Reverse(Cat((0 until 128) map {i => bitmask(i / 8)}))
      val eidx_shifted = eidx << MuxLookup(rd_vew, VEW_8, Array(
         VEW_8  -> UInt(0),
         VEW_16 -> UInt(1),
         VEW_32 -> UInt(2),
         VEW_64 -> UInt(3)))
      val strip_off = eidx_shifted(3,0)
      val shifted_mask = mask << (strip_off << 3)
      val shifted_wdata = wdata << (strip_off << 3)
      (shifted_mask, shifted_wdata)
   }

   // --------------------------------------------------------------
   // Write ports.
   for (wport <- io.write_ports)
   {
      wport.ready := Bool(true)
      when (wport.valid && (wport.bits.addr =/= UInt(0)))
      {

         if (isVector) {
            val (gen_mask, gen_wdata) = toBytes(wport.bits.data, wport.bits.mask, wport.bits.eidx, wport.bits.rd_vew)
            val to_keep = regfile(wport.bits.addr) & ~gen_mask
            val to_write = gen_wdata & gen_mask
            regfile(wport.bits.addr) := to_keep | to_write
         } else {
            regfile(wport.bits.addr) := wport.bits.data
         }
      }
   }
}
