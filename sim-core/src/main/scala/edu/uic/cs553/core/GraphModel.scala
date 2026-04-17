package edu.uic.cs553.core

import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import scala.util.Random

/** Enriched graph model with edge labels and node PDFs on top of NetGameSim topology. */
case class SimNode(id: Int, pdf: Map[String, Double])

case class SimEdge(from: Int, to: Int, allowedTypes: Set[String])

case class EnrichedGraph(
    nodes: Map[Int, SimNode],
    edges: List[SimEdge]
):
  private val logger: Logger = LoggerFactory.getLogger(classOf[EnrichedGraph])

  /** All neighbors reachable from a given node. */
  def outNeighbors(nodeId: Int): List[Int] =
    edges.collect { case e if e.from == nodeId => e.to }

  /** Allowed message types on the edge from -> to. */
  def edgeLabel(from: Int, to: Int): Set[String] =
    edges.find(e => e.from == from && e.to == to)
      .map(_.allowedTypes)
      .getOrElse(Set.empty)

  /** Sample a message type from the node's PDF using the given RNG. */
  def sampleMessage(nodeId: Int, rng: Random): String =
    val node = nodes(nodeId)
    val r = rng.nextDouble()
    var cumulative = 0.0
    node.pdf.find { case (_, p) =>
      cumulative += p
      r < cumulative
    }.map(_._1).getOrElse(node.pdf.keys.head)

object EnrichedGraph:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Build an enriched graph from a raw adjacency list and configuration. */
  def fromConfig(
      nodeIds: Seq[Int],
      adjacency: Map[Int, Seq[Int]],
      config: Config
  ): EnrichedGraph =

    val msgTypes = {
      import scala.jdk.CollectionConverters.*
      config.getStringList("sim.messages.types").asScala.toList
    }
    val defaultLabels = {
      import scala.jdk.CollectionConverters.*
      config.getStringList("sim.edgeLabeling.default").asScala.toSet
    }

    val overrides: Map[(Int, Int), Set[String]] = {
      import scala.jdk.CollectionConverters.*
      if config.hasPath("sim.edgeLabeling.overrides") then
        config.getConfigList("sim.edgeLabeling.overrides").asScala.map { c =>
          (c.getInt("from"), c.getInt("to")) -> c.getStringList("allow").asScala.toSet
        }.toMap
      else Map.empty
    }

    val defaultPdf: Map[String, Double] = {
      import scala.jdk.CollectionConverters.*
      config.getConfigList("sim.traffic.defaultPdf").asScala.map { c =>
        c.getString("msg") -> c.getDouble("p")
      }.toMap
    }

    val perNodePdf: Map[Int, Map[String, Double]] = {
      import scala.jdk.CollectionConverters.*
      if config.hasPath("sim.traffic.perNodePdf") then
        config.getConfigList("sim.traffic.perNodePdf").asScala.map { c =>
          c.getInt("node") -> c.getConfigList("pdf").asScala.map { p =>
            p.getString("msg") -> p.getDouble("p")
          }.toMap
        }.toMap
      else Map.empty
    }

    val nodes = nodeIds.map { id =>
      val pdf = perNodePdf.getOrElse(id, defaultPdf)
      id -> SimNode(id, pdf)
    }.toMap

    val edges = adjacency.toList.flatMap { case (from, tos) =>
      tos.map { to =>
        val allowed = overrides.getOrElse((from, to), defaultLabels)
        SimEdge(from, to, allowed)
      }
    }
    
    logger.info(s"Built enriched graph with ${nodes.size} nodes and ${edges.size} edges")
    EnrichedGraph(nodes, edges)
