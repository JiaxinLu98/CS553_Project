package edu.uic.cs553.algorithms

import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeActor, NodeContext}
import org.slf4j.{Logger, LoggerFactory}

object ItaiRodehRingSize:
  sealed trait IRMsg
  case class Probe(token: Int, originId: Int, hops: Int) extends IRMsg
  case class Decision(token: Int, size: Int, originId: Int) extends IRMsg

/**
 * A ring-size discovery variant inspired by Itai-Rodeh.
 */
class ItaiRodehRingSize extends DistributedAlgorithm:
  import ItaiRodehRingSize.*

  private val logger: Logger = LoggerFactory.getLogger(classOf[ItaiRodehRingSize])

  override val name: String = "ItaiRodehRingSize"

  // Actor-local mutable state. Each DistributedAlgorithm instance is owned by
  // exactly one NodeActor, and an Akka actor processes its mailbox messages
  // sequentially, so these vars are never read or written concurrently and
  // carry no shared-heap race risk. They model per-node ring-probe state
  // (successor, in-flight token, decision latch) that must persist across
  // message handlers and timer ticks.
  private var nextNeighbor: Option[Int] = None
  private var activeToken: Option[Int] = None
  private var decidedSize: Option[Int] = None
  private var startedByTimer: Boolean = false
  private var forwardedDecisionToken: Option[Int] = None

  override def onStart(ctx: NodeContext): Unit =
    nextNeighbor = ctx.neighbors.keys.headOption
    logger.info(s"Node ${ctx.nodeId} ready for ring-size discovery")

  private def startProbe(ctx: NodeContext): Unit =
    if decidedSize.nonEmpty || activeToken.nonEmpty then return
    val token = ctx.rng.nextInt(1000000)
    activeToken = Some(token)
    nextNeighbor.foreach { to =>
      ctx.sendAlgorithmMsg(to, name, Probe(token, ctx.nodeId, hops = 1))
    }
    logger.info(s"Node ${ctx.nodeId} started ring-size probe token=$token")

  override def onMessage(ctx: NodeContext, msg: Any): Unit = msg match
    case NodeActor.AlgorithmMessage(_, algName, data: IRMsg) if algName == name =>
      data match
        case Probe(token, originId, hops) =>
          if decidedSize.nonEmpty && originId != ctx.nodeId then
            ()
          else if originId == ctx.nodeId && activeToken.contains(token) then
            decidedSize = Some(hops)
            forwardedDecisionToken = None
            logger.info(s"Node ${ctx.nodeId} decided ring size = $hops")
            nextNeighbor.foreach { to =>
              ctx.sendAlgorithmMsg(to, name, Decision(token, hops, ctx.nodeId))
            }
          else
            nextNeighbor.foreach { to =>
              ctx.sendAlgorithmMsg(to, name, Probe(token, originId, hops + 1))
            }

        case Decision(token, size, originId) =>
          if decidedSize.isEmpty then
            decidedSize = Some(size)
            logger.info(s"Node ${ctx.nodeId} learned ring size = $size from origin $originId")

          if originId == ctx.nodeId then
            logger.info(s"Node ${ctx.nodeId} completed ring-size announcement for size = $size")
            activeToken = None
          else if forwardedDecisionToken != Some(token) then
            forwardedDecisionToken = Some(token)
            nextNeighbor.foreach { to =>
              ctx.sendAlgorithmMsg(to, name, Decision(token, size, originId))
            }

    case _ => ()

  override def onTick(ctx: NodeContext): Unit =
    if !startedByTimer && decidedSize.isEmpty then
      startedByTimer = true
      startProbe(ctx)
