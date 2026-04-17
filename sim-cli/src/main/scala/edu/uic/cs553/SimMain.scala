package edu.uic.cs553

import com.typesafe.config.{Config, ConfigFactory}
import edu.uic.cs553.algorithms.{BullyAlgorithm, ItaiRodehRingSize, TreeLeaderElection}
import edu.uic.cs553.core.{GraphLoader, InjectionModel, PdfValidator, SimulationArtifact}
import edu.uic.cs553.runtime.{DistributedAlgorithm, SimRunner}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

sealed trait CliCommand
case class Generate(configPath: String, outDir: String) extends CliCommand
case class Run(
    graphPath: String,
    injectFile: Option[String],
    interactive: Boolean,
    runDurationMs: Option[Long]
) extends CliCommand
case class Legacy(configPath: Option[String], outDir: Option[String]) extends CliCommand

/**
 * Main entry point for the simulation.
 *
 * Recommended workflow:
 *   sbt "sim-cli/runMain edu.uic.cs553.SimMain generate --config conf/sim.conf --out outputs/run1"
 *   sbt "sim-cli/runMain edu.uic.cs553.SimMain run --graph outputs/run1/graph.json --inject-file conf/injections.txt"
 */
@main def SimMain(args: String*): Unit =
  val logger: Logger = LoggerFactory.getLogger("SimMain")
  logger.info("=== NetGameSim Akka Distributed Algorithms Simulator ===")

  parseArgs(args.toList) match
    case Generate(configPath, outDir) =>
      val config = loadConfig(Some(configPath), logger)
      val (artifact, rawTopology) = GraphLoader.buildArtifact(config)
      artifact.graph.nodes.values.foreach(n => PdfValidator.validateOrThrow(n.pdf))
      writeRawTopology(rawTopology, Paths.get(outDir, "topology.json"))
      artifact.writeTo(Paths.get(outDir, "graph.json"))
      logger.info(s"Wrote enriched artifact to ${Paths.get(outDir, "graph.json")}")
      logger.info(s"Wrote normalized raw topology to ${Paths.get(outDir, "topology.json")}")

    case Run(graphPath, injectFile, interactive, runDurationMs) =>
      val artifact = SimulationArtifact.readFrom(Paths.get(graphPath))
      artifact.graph.nodes.values.foreach(n => PdfValidator.validateOrThrow(n.pdf))
      val injections = injectFile.map(path => InjectionModel.parseFile(Paths.get(path))).getOrElse(Nil)
      val algorithmFactories = selectAlgorithms(artifact.algorithm, logger)
      SimRunner.run(
        artifact,
        algorithmFactories,
        SimRunner.RunOptions(
          runDurationMsOverride = runDurationMs,
          injections = injections,
          interactive = interactive
        )
      )
      logger.info("=== Simulation finished ===")

    case Legacy(configPath, outDir) =>
      val config = loadConfig(configPath, logger)
      val (artifact, rawTopology) = GraphLoader.buildArtifact(config)
      artifact.graph.nodes.values.foreach(n => PdfValidator.validateOrThrow(n.pdf))
      outDir.foreach { dir =>
        writeRawTopology(rawTopology, Paths.get(dir, "topology.json"))
        artifact.writeTo(Paths.get(dir, "graph.json"))
      }
      val algorithmFactories = selectAlgorithms(artifact.algorithm, logger)
      SimRunner.run(artifact, algorithmFactories)
      logger.info("=== Simulation finished ===")

def loadConfig(configPath: Option[String], logger: Logger): Config =
  configPath match
    case Some(path) =>
      logger.info(s"Loading config from $path")
      ConfigFactory.parseFile(new File(path)).withFallback(ConfigFactory.load()).resolve()
    case None =>
      logger.info("Using default application.conf")
      ConfigFactory.load().resolve()

def selectAlgorithms(algorithm: String, logger: Logger): List[() => DistributedAlgorithm] =
  logger.info(s"Selected algorithm: $algorithm")
  algorithm match
    case "bully"       => List(() => new BullyAlgorithm)
    case "itai-rodeh"  => List(() => new ItaiRodehRingSize)
    case "tree-leader" => List(() => new TreeLeaderElection)
    case "both"        => List(() => new BullyAlgorithm, () => new ItaiRodehRingSize)
    case other =>
      logger.warn(s"Unknown algorithm '$other', running without algorithms")
      Nil

def parseArgs(args: List[String]): CliCommand =
  args match
    case "generate" :: tail =>
      val options = optionMap(tail)
      Generate(
        configPath = options.getOrElse("--config", "conf/sim.conf"),
        outDir = options.getOrElse("--out", "outputs/run")
      )
    case "run" :: tail =>
      val options = optionMap(tail)
      val graphPath = options.getOrElse("--graph", throw new IllegalArgumentException("Missing --graph"))
      Run(
        graphPath = graphPath,
        injectFile = options.get("--inject-file"),
        interactive = options.contains("--interactive"),
        runDurationMs = options.get("--run-ms").map(_.toLong)
      )
    case _ =>
      val options = optionMap(args)
      Legacy(
        configPath = options.get("--config"),
        outDir = options.get("--out")
      )

def optionMap(args: List[String]): Map[String, String] =
  val flags = Set("--interactive")

  @annotation.tailrec
  def loop(rest: List[String], acc: Map[String, String]): Map[String, String] =
    rest match
      case Nil => acc
      case flag :: tail if flags.contains(flag) =>
        loop(tail, acc + (flag -> "true"))
      case key :: value :: tail if key.startsWith("--") =>
        loop(tail, acc + (key -> value))
      case other =>
        throw new IllegalArgumentException(s"Unrecognized arguments: ${other.mkString(" ")}")

  loop(args, Map.empty)

def writeRawTopology(raw: GraphLoader.RawTopology, path: Path): Unit =
  given io.circe.Encoder[GraphLoader.RawTopology] = deriveEncoder
  Files.createDirectories(path.getParent)
  Files.writeString(path, io.circe.Printer.spaces2SortKeys.print(raw.asJson), StandardCharsets.UTF_8)
