package edu.uic.cs553.core

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GraphLoaderTest extends AnyFunSuite with Matchers:

  private val config = ConfigFactory.load()

  test("generateRing creates a ring with correct structure"):
    val graph = GraphLoader.generateRing(config)
    val n = config.getInt("sim.graph.numNodes")
    graph.nodes.size shouldBe n
    graph.nodes.keys.foreach { id =>
      graph.outNeighbors(id) shouldBe List((id + 1) % n)
    }

  test("generateComplete creates a fully connected graph"):
    val graph = GraphLoader.generateComplete(config)
    val n = config.getInt("sim.graph.numNodes")
    graph.nodes.size shouldBe n
    graph.nodes.keys.foreach { id =>
      graph.outNeighbors(id).size shouldBe (n - 1)
    }

  test("loadFromNetGameSimJson parses a valid JSON graph file"):
    val testFile = getClass.getClassLoader.getResource("test-graph.json").getPath
    val graph = GraphLoader.loadFromNetGameSimJson(testFile, config)
    graph.nodes.size shouldBe 3
    graph.edges.size shouldBe 3
    graph.outNeighbors(0) shouldBe List(1)
    graph.outNeighbors(1) shouldBe List(2)
    graph.outNeighbors(2) shouldBe List(0)

  test("loadFromNetGameSimJson applies edge labels from config"):
    val testFile = getClass.getClassLoader.getResource("test-graph.json").getPath
    val graph = GraphLoader.loadFromNetGameSimJson(testFile, config)
    // Default labels from config: ["CONTROL", "PING"]
    graph.edgeLabel(0, 1) shouldBe Set("CONTROL", "PING")
