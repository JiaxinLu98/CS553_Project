package edu.uic.cs553.core

import com.typesafe.config.Config
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

case class TimerInitiator(
    node: Int,
    tickEveryMs: Long,
    mode: String = "pdf",
    fixedMsg: Option[String] = None
)

case class InputInitiator(node: Int)

case class InitiatorSpec(
    timers: List[TimerInitiator],
    inputs: List[InputInitiator]
)

case class TopologySource(
    topology: String,
    numNodes: Int,
    edgeProbability: Double,
    seed: Long,
    rawTopologyFile: Option[String]
)

case class SimulationArtifact(
    formatVersion: Int,
    algorithm: String,
    runDurationMs: Long,
    messageTypes: List[String],
    graph: EnrichedGraph,
    initiators: InitiatorSpec,
    source: TopologySource
):
  def writeTo(path: Path): Unit =
    val json = SimulationArtifact.jsonPrinter.print(this.asJson)
    Files.createDirectories(path.getParent)
    Files.writeString(path, json, StandardCharsets.UTF_8)

object SimulationArtifact:
  private given Encoder[SimNode] = deriveEncoder
  private given Decoder[SimNode] = deriveDecoder
  private given Encoder[SimEdge] = deriveEncoder
  private given Decoder[SimEdge] = deriveDecoder
  private given Encoder[EnrichedGraph] = deriveEncoder
  private given Decoder[EnrichedGraph] = deriveDecoder
  private given Encoder[TimerInitiator] = deriveEncoder
  private given Decoder[TimerInitiator] = deriveDecoder
  private given Encoder[InputInitiator] = deriveEncoder
  private given Decoder[InputInitiator] = deriveDecoder
  private given Encoder[InitiatorSpec] = deriveEncoder
  private given Decoder[InitiatorSpec] = deriveDecoder
  private given Encoder[TopologySource] = deriveEncoder
  private given Decoder[TopologySource] = deriveDecoder
  given Encoder[SimulationArtifact] = deriveEncoder
  given Decoder[SimulationArtifact] = deriveDecoder

  val jsonPrinter = io.circe.Printer.spaces2SortKeys

  def readFrom(path: Path): SimulationArtifact =
    val content = Files.readString(path, StandardCharsets.UTF_8)
    decode[SimulationArtifact](content).fold(throw _, identity)

  def fromConfig(graph: EnrichedGraph, config: Config, source: TopologySource): SimulationArtifact =
    val timers =
      if config.hasPath("sim.initiators.timers") then
        config.getConfigList("sim.initiators.timers").asScala.toList.map { timerCfg =>
          TimerInitiator(
            node = timerCfg.getInt("node"),
            tickEveryMs = timerCfg.getLong("tickEveryMs"),
            mode = if timerCfg.hasPath("mode") then timerCfg.getString("mode") else "pdf",
            fixedMsg = Option.when(timerCfg.hasPath("fixedMsg"))(timerCfg.getString("fixedMsg"))
          )
        }
      else Nil

    val inputs =
      if config.hasPath("sim.initiators.inputs") then
        config.getConfigList("sim.initiators.inputs").asScala.toList.map { inputCfg =>
          InputInitiator(inputCfg.getInt("node"))
        }
      else Nil

    val messageTypes =
      if config.hasPath("sim.messages.types") then
        config.getStringList("sim.messages.types").asScala.toList
      else Nil

    SimulationArtifact(
      formatVersion = 1,
      algorithm = config.getString("sim.algorithm"),
      runDurationMs = config.getLong("sim.runDurationMs"),
      messageTypes = messageTypes,
      graph = graph,
      initiators = InitiatorSpec(timers, inputs),
      source = source
    )
