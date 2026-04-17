package edu.uic.cs553.core

import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SimulationArtifactTest extends AnyFunSuite with Matchers:

  test("artifact round-trips through graph.json serialization"):
    val config = ConfigFactory.parseString(
      """
        |netgamesim {
        |  topology = "ring"
        |  numNodes = 4
        |  edgeProbability = 0.0
        |  seed = 99
        |}
        |sim {
        |  algorithm = "both"
        |  runDurationMs = 2000
        |  messages {
        |    types = ["CONTROL", "PING", "WORK"]
        |  }
        |  edgeLabeling {
        |    default = ["CONTROL", "PING", "WORK"]
        |    overrides = []
        |  }
        |  traffic {
        |    defaultPdf = [
        |      { msg = "PING", p = 0.5 },
        |      { msg = "WORK", p = 0.5 }
        |    ]
        |    perNodePdf = []
        |  }
        |  initiators {
        |    timers = [{ node = 0, tickEveryMs = 100, mode = "pdf" }]
        |    inputs = [{ node = 1 }]
        |  }
        |}
        |""".stripMargin
    )

    val (artifact, _) = GraphLoader.buildArtifact(config)
    val path = Files.createTempFile("graph", ".json")
    artifact.writeTo(path)

    val loaded = SimulationArtifact.readFrom(path)
    loaded.algorithm shouldBe "both"
    loaded.graph.nodes.keySet shouldBe artifact.graph.nodes.keySet
    loaded.initiators.inputs shouldBe List(InputInitiator(1))
    loaded.initiators.timers.head.tickEveryMs shouldBe 100L
