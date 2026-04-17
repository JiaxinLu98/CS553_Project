package edu.uic.cs553.algorithms

import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeActor, NodeContext}
import org.slf4j.{Logger, LoggerFactory}

object BullyAlgorithm:
  sealed trait BullyMsg
  case class Election(fromId: Int) extends BullyMsg
  case class Ok(fromId: Int) extends BullyMsg
  case class Coordinator(leaderId: Int) extends BullyMsg

/**
 * Bully leader election for a complete graph with unique, comparable node IDs.
 */
class BullyAlgorithm extends DistributedAlgorithm:
  import BullyAlgorithm.*

  private val logger: Logger = LoggerFactory.getLogger(classOf[BullyAlgorithm])

  override val name: String = "BullyAlgorithm"

  // Actor-local mutable state. Each DistributedAlgorithm instance is owned by
  // exactly one NodeActor, and an Akka actor processes its mailbox messages
  // sequentially, so these vars are never read or written concurrently and
  // carry no shared-heap race risk. They model per-node election state that
  // must persist across message handlers and timer ticks.
  private var coordinatorId: Int = -1
  private var electionInProgress: Boolean = false
  private var receivedOk: Boolean = false
  private var ticksSinceElection: Int = 0
  private var startupElectionTriggered: Boolean = false
  private val electionTimeout: Int = 4
  private val coordinatorWaitTimeout: Int = 8

  override def onStart(ctx: NodeContext): Unit =
    logger.info(s"Node ${ctx.nodeId} ready for Bully election")

  private def higherNeighbors(ctx: NodeContext): List[Int] =
    ctx.neighbors.keys.filter(_ > ctx.nodeId).toList.sorted

  private def becomeCoordinator(ctx: NodeContext): Unit =
    coordinatorId = ctx.nodeId
    electionInProgress = false
    receivedOk = false
    ticksSinceElection = 0
    logger.info(s"Node ${ctx.nodeId} elected itself as coordinator")
    ctx.broadcastAlgorithmMsg(name, Coordinator(ctx.nodeId))

  private def startElection(ctx: NodeContext, reason: String): Unit =
    val higher = higherNeighbors(ctx)
    electionInProgress = true
    receivedOk = false
    ticksSinceElection = 0
    logger.info(s"Node ${ctx.nodeId} starting Bully election ($reason)")
    if higher.isEmpty then
      becomeCoordinator(ctx)
    else
      higher.foreach(to => ctx.sendAlgorithmMsg(to, name, Election(ctx.nodeId)))
      logger.debug(s"Node ${ctx.nodeId} sent ELECTION to higher nodes: $higher")

  override def onMessage(ctx: NodeContext, msg: Any): Unit = msg match
    case NodeActor.AlgorithmMessage(_, algName, data: BullyMsg) if algName == name =>
      data match
        case Election(fromId) =>
          if ctx.nodeId > fromId then
            ctx.sendAlgorithmMsg(fromId, name, Ok(ctx.nodeId))
            logger.debug(s"Node ${ctx.nodeId} sent OK to $fromId")
            if !electionInProgress && coordinatorId != ctx.nodeId then
              startElection(ctx, s"received election from $fromId")

        case Ok(fromId) =>
          receivedOk = true
          ticksSinceElection = 0
          logger.debug(s"Node ${ctx.nodeId} received OK from $fromId")

        case Coordinator(leaderId) =>
          if leaderId >= ctx.nodeId then
            coordinatorId = leaderId
            electionInProgress = false
            receivedOk = false
            ticksSinceElection = 0
            logger.info(s"Node ${ctx.nodeId} acknowledges coordinator = $leaderId")
          else if !electionInProgress then
            logger.info(s"Node ${ctx.nodeId} ignoring lower coordinator $leaderId and restarting election")
            startElection(ctx, s"observed lower coordinator $leaderId")

    case _ => ()

  override def onTick(ctx: NodeContext): Unit =
    if !startupElectionTriggered && coordinatorId == -1 && !electionInProgress then
      startupElectionTriggered = true
      startElection(ctx, "startup timer")
    else if electionInProgress then
      ticksSinceElection += 1
      if !receivedOk && ticksSinceElection >= electionTimeout then
        becomeCoordinator(ctx)
      else if receivedOk && ticksSinceElection >= coordinatorWaitTimeout then
        startElection(ctx, "coordinator announcement timeout")
