package edu.uic.cs553.algorithms

import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeActor, NodeContext}
import org.slf4j.{Logger, LoggerFactory}

object TreeLeaderElection:
  sealed trait TreeMsg
  case class Vote(bestId: Int, fromId: Int) extends TreeMsg
  case class Search(targetId: Int) extends TreeMsg
  case class Leader(leaderId: Int) extends TreeMsg

/**
 * Saturation-based leader election on an undirected tree.
 *
 * Assumptions: network is a tree (n-1 undirected edges, connected, acyclic),
 * every node has a unique comparable ID (node ID), FIFO per-edge messaging.
 *
 * Protocol:
 *   1. Each leaf sends Vote(selfId, self) on its only edge at start.
 *   2. An internal node, after receiving Vote from (degree-1) neighbors,
 *      forwards Vote(maxSoFar, self) to the one silent neighbor. Each node
 *      remembers `bestEdge` = the neighbor that supplied the current best ID.
 *   3. When a node has received Vote from ALL neighbors it is "saturated".
 *      - If bestId == selfId, this node is the global max; declare Leader.
 *      - Else, forward Search(bestId) along bestEdge (which is the direction
 *        the global max came from).
 *   4. A non-saturated node receiving Search(targetId):
 *      - If selfId == targetId, declare Leader.
 *      - Else forward Search along bestEdge.
 *   5. Leader(id) is broadcast down the tree; each node propagates it to all
 *      neighbors except the sender. Handlers are idempotent via `decided`.
 */
class TreeLeaderElection extends DistributedAlgorithm:
  import TreeLeaderElection.*

  private val logger: Logger = LoggerFactory.getLogger(classOf[TreeLeaderElection])

  override val name: String = "TreeLeaderElection"

  // Actor-local mutable state. An Akka actor processes messages sequentially
  // (one at a time from its mailbox) and each DistributedAlgorithm instance is
  // owned by exactly one NodeActor, so these fields are never read or written
  // concurrently. The `var`s and the mutable Set below therefore carry no
  // shared-heap race risk; they exist to model per-node protocol state (best
  // candidate seen, provenance of that best vote, saturation progress, and
  // decision latch) that must persist across message handlers.
  private var bestId: Int = -1
  private var bestEdge: Option[Int] = None
  private val receivedFrom: scala.collection.mutable.Set[Int] = scala.collection.mutable.Set.empty
  private var sentToSilent: Boolean = false
  private var decided: Boolean = false
  private var leaderId: Option[Int] = None

  override def onStart(ctx: NodeContext): Unit =
    bestId = ctx.nodeId
    bestEdge = None
    val degree = ctx.neighbors.size
    logger.info(s"Node ${ctx.nodeId} starting tree-leader-election (degree=$degree)")
    if degree == 0 then
      declareLeader(ctx)
    else if degree == 1 then
      val only = ctx.neighbors.keys.head
      ctx.sendAlgorithmMsg(only, name, Vote(bestId, ctx.nodeId))
      sentToSilent = true
      logger.debug(s"Node ${ctx.nodeId} (leaf) sent initial Vote to $only")

  override def onMessage(ctx: NodeContext, msg: Any): Unit = msg match
    case am @ NodeActor.AlgorithmMessage(_, algName, data: TreeMsg) if algName == name =>
      data match
        case Vote(votedBest, fromId)  => handleVote(ctx, votedBest, fromId)
        case Search(targetId)         => handleSearch(ctx, targetId)
        case Leader(id)               => handleLeader(ctx, id, senderId = am.from)
    case _ => ()

  private def handleVote(ctx: NodeContext, votedBest: Int, fromId: Int): Unit =
    if decided then return
    if votedBest > bestId then
      bestId = votedBest
      bestEdge = Some(fromId)
    receivedFrom += fromId
    val silent = ctx.neighbors.keys.toSet -- receivedFrom
    if silent.size == 1 && !sentToSilent then
      val s = silent.head
      ctx.sendAlgorithmMsg(s, name, Vote(bestId, ctx.nodeId))
      sentToSilent = true
      logger.debug(s"Node ${ctx.nodeId} forwarded Vote(best=$bestId) to silent neighbor $s")
    else if silent.isEmpty then onSaturated(ctx)

  private def onSaturated(ctx: NodeContext): Unit =
    if decided then return
    if bestId == ctx.nodeId then
      declareLeader(ctx)
    else
      bestEdge match
        case Some(next) =>
          logger.debug(s"Node ${ctx.nodeId} saturated (bestId=$bestId), forwarding Search to $next")
          ctx.sendAlgorithmMsg(next, name, Search(bestId))
        case None =>
          logger.warn(s"Node ${ctx.nodeId} saturated with bestId=$bestId but no bestEdge — declaring self anyway")
          declareLeader(ctx)

  private def handleSearch(ctx: NodeContext, targetId: Int): Unit =
    if decided then return
    if targetId == ctx.nodeId then
      declareLeader(ctx)
    else
      bestEdge match
        case Some(next) =>
          logger.debug(s"Node ${ctx.nodeId} forwarding Search($targetId) toward $next")
          ctx.sendAlgorithmMsg(next, name, Search(targetId))
        case None =>
          logger.warn(s"Node ${ctx.nodeId} got Search($targetId) but no bestEdge yet; dropping")

  private def declareLeader(ctx: NodeContext): Unit =
    if decided then return
    leaderId = Some(ctx.nodeId)
    decided = true
    logger.info(s"Node ${ctx.nodeId} elected itself LEADER (tree)")
    ctx.broadcastAlgorithmMsg(name, Leader(ctx.nodeId))

  private def handleLeader(ctx: NodeContext, id: Int, senderId: Int): Unit =
    if decided then
      if !leaderId.contains(id) then
        logger.warn(s"Node ${ctx.nodeId} already decided leader=${leaderId.getOrElse(-1)}, ignoring Leader($id) from $senderId")
    else
      leaderId = Some(id)
      decided = true
      logger.info(s"Node ${ctx.nodeId} acknowledges LEADER=$id (tree)")
      ctx.neighbors.keys.filter(_ != senderId).foreach { to =>
        ctx.sendAlgorithmMsg(to, name, Leader(id))
      }
