package edu.uic.cs553.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PdfValidatorTest extends AnyFunSuite with Matchers:

  test("valid PDF sums to 1.0"):
    val pdf = Map("PING" -> 0.5, "WORK" -> 0.3, "GOSSIP" -> 0.2)
    PdfValidator.validate(pdf) shouldBe true

  test("invalid PDF does not sum to 1.0"):
    val pdf = Map("PING" -> 0.5, "WORK" -> 0.3)
    PdfValidator.validate(pdf) shouldBe false

  test("PDF within tolerance is accepted"):
    val pdf = Map("PING" -> 0.505, "WORK" -> 0.5)
    PdfValidator.validate(pdf) shouldBe true
