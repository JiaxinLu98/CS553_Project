package edu.uic.cs553.runtime

import akka.actor.{ActorRef, ActorSystem}
import scala.util.Random

/**
 * Context available to algorithm modules during message handling.
 * Provides node identity, neighbor references, and helpers for sending.
 */
case class NodeContext(
    nodeId: Int,
    neighbors: Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    selfRef: ActorRef,
    system: ActorSystem,
    rng: Random
):
  /** Send an algorithm-specific message to a neighbor. */
  def sendAlgorithmMsg(to: Int, algName: String, data: Any): Unit =
    neighbors.get(to).foreach { ref =>
      ref ! NodeActor.AlgorithmMessage(from = nodeId, algName = algName, data = data)
    }

  /** Send an algorithm message to all neighbors. */
  def broadcastAlgorithmMsg(algName: String, data: Any): Unit =
    neighbors.foreach { case (toId, ref) =>
      ref ! NodeActor.AlgorithmMessage(from = nodeId, algName = algName, data = data)
    }

/**
 * Plug-in interface for distributed algorithms.
 * Each algorithm is a module that reacts to lifecycle events.
 */
trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: NodeContext): Unit
  def onMessage(ctx: NodeContext, msg: Any): Unit
  def onTick(ctx: NodeContext): Unit = ()
