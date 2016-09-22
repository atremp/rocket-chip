package util

import Chisel._

/** Divide the clock by 2 */
class ClockDivider2 extends Module {
  val io = new Bundle {
    val reset_out = Bool(OUTPUT)
    val clock_out = Clock(OUTPUT)
  }

  val clock_reg = Reg(Bool())
  clock_reg := !clock_reg

  // Hold reset_out high until positive edge of clock_out
  val reset_reg = RegEnable(Bool(false), Bool(true), clock_reg)

  io.clock_out := clock_reg.asClock
  io.reset_out := reset_reg
}

class ClockDivider(n: Int) extends Module {
  val io = new Bundle {
    val reset_out = Bool(OUTPUT)
    val clock_out = Clock(OUTPUT)
  }

  require(isPow2(n), "Clock divider period must be power of 2")
  require(n >= 2, "Clock divider must divide by at least 2")

  val nDividers = log2Ceil(n)
  val dividers = Seq.fill(nDividers) { Module(new ClockDivider2) }

  dividers.init.zip(dividers.tail).map { case (last, next) =>
    next.clock := last.io.clock_out
    next.reset := last.io.reset_out
  }

  io.reset_out := dividers.last.io.reset_out
  io.clock_out := dividers.last.io.clock_out
}
