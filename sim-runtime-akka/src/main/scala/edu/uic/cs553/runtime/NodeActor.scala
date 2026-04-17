package edu.uic.cs553.runtime

import akka.actor.{Actor, ActorRef, Props, Timers}
import edu.uic.cs553.core.TimerInitiator
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.duration.*
import scala.util.Random

/**
 * Each graph node becomes a NodeActor. It owns its neighbor map,
 * edge label constraints, and a PDF for traffic generation.
 * Algorithm modules plug in via the DistributedAlgorithm trait.
 */
object NodeActor:
  def props(id: Int, seed: Long): Props = Props(new NodeActor(id, seed))

  sealed trait Msg

  final case class Init(
      neighbors: Map[Int, ActorRef],
      allowedOnEdge: Map[Int, Set[String]],
      pdf: Map[String, Double],
      timerConfig: Option[TimerInitiator],
      algorithms: List[DistributedAlgorithm]
  ) extends Msg

  final case class ExternalInput(kind: String, payload: String) extends Msg
  final case class Envelope(from: Int, kind: String, payload: String) extends Msg
  final case class AlgorithmMessage(from: Int, algName: String, data: Any) extends Msg
  case object StopSim extends Msg
  private case object Tick extends Msg

final class NodeActor(val id: Int, seed: Long) extends Actor with Timers:
  import NodeActor.*
  private val logger: Logger = LoggerFactory.getLogger(s"NodeActor-$id")
  private val rng = new Random(seed + id)

  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty
  private var algorithms: List[DistributedAlgorithm] = Nil
  private var timerMode: String = "pdf"
  private var fixedTimerMessage: Option[String] = None

  // Metrics counters: actor-local mutable state is justified because
  // Akka actors process messages sequentially, so there is no concurrent access.
  private var sentCount: Int = 0
  private var receivedCount: Int = 0

  override def receive: Receive =
    case Init(nbrs, allow, pdf0, timerCfg, algs) =>
      neighbors = nbrs
      allowedOnEdge = allow
      pdf = pdf0
      algorithms = algs
      timerMode = timerCfg.map(_.mode).getOrElse("pdf")
      fixedTimerMessage = timerCfg.flatMap(_.fixedMsg)
      timerCfg.foreach { cfg =>
        timers.startTimerAtFixedRate("tick", Tick, cfg.tickEveryMs.millis)
      }
      val ctx = makeContext
      algorithms.foreach(_.onStart(ctx))
      logger.info(s"Node $id initialized with ${nbrs.size} neighbors, timer=${timerCfg.nonEmpty}")

    case Tick =>
      val kind = timerMode match
        case "fixed" => fixedTimerMessage.getOrElse(sampleFromPdf)
        case _       => sampleFromPdf
      sendToOneNeighbor(kind, s"tick-from-$id")
      val ctx = makeContext
      algorithms.foreach(_.onTick(ctx))

    case ExternalInput(kind, payload) =>
      logger.info(s"Node $id received external input: $kind")
      receivedCount += 1
      val ctx = makeContext
      val env = Envelope(from = -1, kind = kind, payload = payload)
      algorithms.foreach(_.onMessage(ctx, env))
      sendToOneNeighbor(kind, payload)

    case env @ Envelope(from, kind, payload) =>
      receivedCount += 1
      logger.debug(s"Node $id received $kind from $from")
      val ctx = makeContext
      algorithms.foreach(_.onMessage(ctx, env))

    case msg @ AlgorithmMessage(from, algName, data) =>
      receivedCount += 1
      val ctx = makeContext
      algorithms.foreach(_.onMessage(ctx, msg))

    case StopSim =>
      timers.cancelAll()
      logger.info(s"Node $id stopping. Sent=$sentCount, Received=$receivedCount")
      context.stop(self)

  private def sendToOneNeighbor(kind: String, payload: String): Unit =
    val eligible = neighbors.keys.filter { to =>
      allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
    }.toList
    if eligible.nonEmpty then
      val target = eligible(rng.nextInt(eligible.size))
      neighbors(target) ! Envelope(from = id, kind = kind, payload = payload)
      sentCount += 1

  private def sampleFromPdf: String =
    val r = rng.nextDouble()
    var cumulative = 0.0
    pdf.find { case (_, p) =>
      cumulative += p
      r < cumulative
    }.map(_._1).getOrElse(pdf.keys.head)

  /** Create a NodeContext for algorithm modules. */
  private def makeContext: NodeContext =
    NodeContext(id, neighbors, allowedOnEdge, self, context.system, rng)
