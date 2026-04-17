package edu.uic.cs553.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class GraphModelTest extends AnyFunSuite with Matchers:

  private val graph = EnrichedGraph(
    nodes = Map(
      0 -> SimNode(0, Map("PING" -> 0.6, "WORK" -> 0.4)),
      1 -> SimNode(1, Map("PING" -> 0.5, "WORK" -> 0.5)),
      2 -> SimNode(2, Map("PING" -> 1.0))
    ),
    edges = List(
      SimEdge(0, 1, Set("PING", "WORK")),
      SimEdge(1, 2, Set("PING")),
      SimEdge(2, 0, Set("PING", "WORK"))
    )
  )

  test("outNeighbors returns correct neighbors"):
    graph.outNeighbors(0) shouldBe List(1)
    graph.outNeighbors(1) shouldBe List(2)
    graph.outNeighbors(2) shouldBe List(0)

  test("edgeLabel returns correct labels"):
    graph.edgeLabel(0, 1) shouldBe Set("PING", "WORK")
    graph.edgeLabel(1, 2) shouldBe Set("PING")

  test("edgeLabel returns empty for non-existent edge"):
    graph.edgeLabel(0, 2) shouldBe Set.empty

  test("sampleMessage returns a valid message type"):
    val rng = new Random(42)
    val msg = graph.sampleMessage(0, rng)
    Set("PING", "WORK") should contain(msg)
