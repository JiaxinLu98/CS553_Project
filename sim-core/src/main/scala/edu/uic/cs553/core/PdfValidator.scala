package edu.uic.cs553.core

import org.slf4j.{Logger, LoggerFactory}

/** Validates that probability distributions sum to 1.0 within tolerance. */
object PdfValidator:
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val Tolerance = 0.01

  def validate(pdf: Map[String, Double]): Boolean =
    val sum = pdf.values.sum
    val valid = math.abs(sum - 1.0) < Tolerance
    if !valid then
      logger.error(s"PDF does not sum to 1.0 (sum=$sum): $pdf")
    valid

  def validateOrThrow(pdf: Map[String, Double]): Unit =
    require(validate(pdf), s"PDF probabilities must sum to 1.0, got ${pdf.values.sum}")
