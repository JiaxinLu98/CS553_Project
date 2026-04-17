package edu.uic.cs553.core

import com.typesafe.config.Config
import io.circe.*
import io.circe.parser.*
import org.slf4j.{Logger, LoggerFactory}
import scala.io.Source
import scala.util.{Random, Using}

/**
 * Loads graphs from NetGameSim JSON output or generates synthetic topologies
 * (ring, complete) for algorithm-specific testing.
 */
object GraphLoader:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  case class RawTopology(
      nodeIds: List[Int],
      adjacency: Map[Int, List[Int]]
  )

  def buildArtifact(config: Config): (SimulationArtifact, RawTopology) =
    val rawTopologyFile = optionalString(config, "netgamesim.rawGraphFile", "sim.graph.rawGraphFile")
    val rawTopology = rawTopologyFile match
      case Some(path) => loadRawTopologyFromNetGameSimJson(path)
      case None       => generateRawTopology(config)

    val topology = configString(config, "netgamesim.topology", "sim.graph.topology")
    val numNodes = configInt(config, "netgamesim.numNodes", "sim.graph.numNodes")
    val edgeProbability = configDouble(config, "netgamesim.edgeProbability", "sim.graph.edgeProbability")
    val seed = configLong(config, "netgamesim.seed", "sim.graph.seed")

    val graph = EnrichedGraph.fromConfig(rawTopology.nodeIds, rawTopology.adjacency, config)
    val source = TopologySource(
      topology = topology,
      numNodes = numNodes,
      edgeProbability = edgeProbability,
      seed = seed,
      rawTopologyFile = rawTopologyFile
    )
    SimulationArtifact.fromConfig(graph, config, source) -> rawTopology

  /**
   * Load a graph from a NetGameSim JSON file.
   *
   * NetGameSim JSON format: line 1 is a JSON array of NodeObject,
   * line 2 is a JSON array of Action (edge). We extract node IDs
   * and (fromNode.id, toNode.id) pairs to build the adjacency list.
   */
  def loadFromNetGameSimJson(filePath: String, config: Config): EnrichedGraph =
    val rawTopology = loadRawTopologyFromNetGameSimJson(filePath)
    EnrichedGraph.fromConfig(rawTopology.nodeIds, rawTopology.adjacency, config)

  def loadRawTopologyFromNetGameSimJson(filePath: String): RawTopology =
    logger.info(s"Loading NetGameSim graph from $filePath")
    val content = Using(Source.fromFile(filePath))(_.mkString).get
    val lines = content.split("\n", 2)
    require(lines.length == 2, s"Expected 2 lines in NetGameSim JSON, got ${lines.length}")

    val nodesJson = parse(lines(0)).getOrElse(
      throw new RuntimeException(s"Failed to parse nodes JSON from $filePath")
    )
    val edgesJson = parse(lines(1)).getOrElse(
      throw new RuntimeException(s"Failed to parse edges JSON from $filePath")
    )

    val nodeIds: List[Int] = nodesJson.asArray
      .getOrElse(throw new RuntimeException("Nodes JSON is not an array"))
      .toList
      .flatMap(_.hcursor.get[Int]("id").toOption)

    val adjacency: Map[Int, Seq[Int]] = edgesJson.asArray
      .getOrElse(throw new RuntimeException("Edges JSON is not an array"))
      .toList
      .flatMap { edgeJson =>
        val cursor = edgeJson.hcursor
        val fromId = cursor.downField("fromNode").get[Int]("id").toOption
        val toId = cursor.downField("toNode").get[Int]("id").toOption
        (fromId, toId) match
          case (Some(f), Some(t)) => Some((f, t))
          case _ => None
      }
      .groupBy(_._1)
      .map { case (from, pairs) => from -> pairs.map(_._2) }

    val fullAdjacency = nodeIds.map { id =>
      id -> adjacency.getOrElse(id, Seq.empty).toList
    }.toMap

    val totalEdges = fullAdjacency.values.map(_.size).sum
    logger.info(s"Loaded NetGameSim graph: ${nodeIds.size} nodes, $totalEdges edges")
    RawTopology(nodeIds, fullAdjacency)

  def generateRawTopology(config: Config): RawTopology =
    configString(config, "netgamesim.topology", "sim.graph.topology") match
      case "ring"     => generateRingRaw(config)
      case "complete" => generateCompleteRaw(config)
      case "random"   => generateRandomRaw(config)
      case "tree"     => generateTreeRaw(config)
      case other =>
        throw new IllegalArgumentException(s"Unsupported topology '$other'")

  /** Build a ring topology for Itai-Rodeh ring size algorithm. */
  def generateRing(config: Config): EnrichedGraph =
    val raw = generateRingRaw(config)
    EnrichedGraph.fromConfig(raw.nodeIds, raw.adjacency, config)

  /** Build a fully connected topology for Bully algorithm. */
  def generateComplete(config: Config): EnrichedGraph =
    val raw = generateCompleteRaw(config)
    EnrichedGraph.fromConfig(raw.nodeIds, raw.adjacency, config)

  def generateRingRaw(config: Config): RawTopology =
    val n = configInt(config, "netgamesim.numNodes", "sim.graph.numNodes")
    val nodeIds = (0 until n).toList
    val adjacency = nodeIds.map { id =>
      id -> List((id + 1) % n)
    }.toMap
    logger.info(s"Generated ring graph with $n nodes")
    RawTopology(nodeIds, adjacency)

  def generateCompleteRaw(config: Config): RawTopology =
    val n = configInt(config, "netgamesim.numNodes", "sim.graph.numNodes")
    val nodeIds = (0 until n).toList
    val adjacency = nodeIds.map { id =>
      id -> nodeIds.filter(_ != id)
    }.toMap
    logger.info(s"Generated complete graph with $n nodes")
    RawTopology(nodeIds, adjacency)

  def generateRandomRaw(config: Config): RawTopology =
    val n = configInt(config, "netgamesim.numNodes", "sim.graph.numNodes")
    val p = configDouble(config, "netgamesim.edgeProbability", "sim.graph.edgeProbability")
    val seed = configLong(config, "netgamesim.seed", "sim.graph.seed")
    val rng = new Random(seed)
    val nodeIds = (0 until n).toList
    val adjacency = nodeIds.map { from =>
      val tos = nodeIds.filter(to => from != to && rng.nextDouble() < p)
      from -> tos
    }.toMap
    logger.info(s"Generated random graph with $n nodes, edgeProbability=$p, seed=$seed")
    RawTopology(nodeIds, adjacency)

  /** Build a random recursive tree for tree-based algorithms (Leader election in trees). */
  def generateTree(config: Config): EnrichedGraph =
    val raw = generateTreeRaw(config)
    EnrichedGraph.fromConfig(raw.nodeIds, raw.adjacency, config)

  def generateTreeRaw(config: Config): RawTopology =
    val n = configInt(config, "netgamesim.numNodes", "sim.graph.numNodes")
    val seed = configLong(config, "netgamesim.seed", "sim.graph.seed")
    val rng = new Random(seed)
    val nodeIds = (0 until n).toList
    val adj = scala.collection.mutable.Map[Int, List[Int]]()
    nodeIds.foreach(id => adj(id) = Nil)
    (1 until n).foreach { i =>
      val parent = rng.nextInt(i)
      adj(i) = parent :: adj(i)
      adj(parent) = i :: adj(parent)
    }
    logger.info(s"Generated random tree with $n nodes, seed=$seed")
    RawTopology(nodeIds, adj.toMap)

  private def configString(config: Config, preferredPath: String, fallbackPath: String): String =
    if config.hasPath(preferredPath) then config.getString(preferredPath)
    else config.getString(fallbackPath)

  private def configInt(config: Config, preferredPath: String, fallbackPath: String): Int =
    if config.hasPath(preferredPath) then config.getInt(preferredPath)
    else config.getInt(fallbackPath)

  private def configLong(config: Config, preferredPath: String, fallbackPath: String): Long =
    if config.hasPath(preferredPath) then config.getLong(preferredPath)
    else config.getLong(fallbackPath)

  private def configDouble(config: Config, preferredPath: String, fallbackPath: String): Double =
    if config.hasPath(preferredPath) then config.getDouble(preferredPath)
    else config.getDouble(fallbackPath)

  private def optionalString(config: Config, preferredPath: String, fallbackPath: String): Option[String] =
    if config.hasPath(preferredPath) then Some(config.getString(preferredPath))
    else if config.hasPath(fallbackPath) then Some(config.getString(fallbackPath))
    else None
