package edu.uic.cs553.runtime

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.after
import edu.uic.cs553.core.{InjectionModel, InteractiveCommand, ScheduledInjection, SimulationArtifact}
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.{Await, Future, Promise, blocking}
import scala.concurrent.duration.*
import scala.io.StdIn

/**
 * Builds an Akka actor system from an enriched artifact and runs the simulation
 * for a configured duration.
 */
object SimRunner:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  case class RunOptions(
      runDurationMsOverride: Option[Long] = None,
      injections: List[ScheduledInjection] = Nil,
      interactive: Boolean = false
  )

  def run(
      artifact: SimulationArtifact,
      algorithms: List[() => DistributedAlgorithm],
      options: RunOptions = RunOptions()
  ): Unit =
    val system = ActorSystem("sim")
    val seed = artifact.source.seed
    val runDurationMs = options.runDurationMsOverride.getOrElse(artifact.runDurationMs)
    val graph = artifact.graph
    val timerConfigs = artifact.initiators.timers.map(t => t.node -> t).toMap
    val inputNodes = artifact.initiators.inputs.map(_.node).toSet

    logger.info("Creating actor system and mapping graph nodes to actors")

    val nodeRefs: Map[Int, ActorRef] = graph.nodes.keys.map { id =>
      id -> system.actorOf(NodeActor.props(id, seed), s"node-$id")
    }.toMap

    validateInjections(nodeRefs.keySet, inputNodes, options.injections)

    graph.nodes.keys.foreach { id =>
      val outNbrs = graph.outNeighbors(id)
      val nbrs: Map[Int, ActorRef] = outNbrs.flatMap(to => nodeRefs.get(to).map(to -> _)).toMap
      val allowedOnEdge: Map[Int, Set[String]] = outNbrs.map(to => to -> graph.edgeLabel(id, to)).toMap
      val pdf = graph.nodes(id).pdf

      nodeRefs(id) ! NodeActor.Init(
        neighbors = nbrs,
        allowedOnEdge = allowedOnEdge,
        pdf = pdf,
        timerConfig = timerConfigs.get(id),
        algorithms = algorithms.map(_())
      )
    }

    logger.info(s"All ${graph.nodes.size} node actors initialized, running for ${runDurationMs}ms")

    given ec: scala.concurrent.ExecutionContext = system.dispatcher

    options.injections.foreach { injection =>
      system.scheduler.scheduleOnce(injection.timeMs.millis) {
        nodeRefs(injection.nodeId) ! NodeActor.ExternalInput(injection.msgType, injection.payload)
        logger.info(s"Injected ${injection.msgType} into node ${injection.nodeId} at ${injection.timeMs}ms")
      }
    }

    val stopRequested = Promise[Unit]()
    if options.interactive then
      startInteractiveLoop(nodeRefs, inputNodes, stopRequested)

    val runFuture = Future.firstCompletedOf(
      List(
        after(runDurationMs.millis, system.scheduler)(Future.successful(())),
        stopRequested.future
      )
    )
    Await.result(runFuture, runDurationMs.millis + 5.seconds)

    logger.info("Simulation time elapsed, stopping actors")
    nodeRefs.values.foreach(_ ! NodeActor.StopSim)
    Thread.sleep(2000)
    system.terminate()
    Await.result(system.whenTerminated, 30.seconds)
    logger.info("Simulation complete, actor system terminated")

  private def validateInjections(
      nodeIds: Set[Int],
      inputNodes: Set[Int],
      injections: List[ScheduledInjection]
  ): Unit =
    injections.foreach { injection =>
      require(nodeIds.contains(injection.nodeId), s"Injection targets missing node ${injection.nodeId}")
      require(
        inputNodes.contains(injection.nodeId),
        s"Injection targets node ${injection.nodeId}, but it is not configured as an input node"
      )
    }

  private def startInteractiveLoop(
      nodeRefs: Map[Int, ActorRef],
      inputNodes: Set[Int],
      stopRequested: Promise[Unit]
  )(using scala.concurrent.ExecutionContext): Unit =
    Future {
      blocking {
        println("Interactive injection ready. Commands: inject node_id msg_type payload | help | quit")
        var continue = true
        while continue && !stopRequested.isCompleted do
          Option(StdIn.readLine()) match
            case None =>
              continue = false
            case Some(line) =>
              InjectionModel.parseInteractive(line) match
                case InteractiveCommand.NoOp =>
                  ()
                case InteractiveCommand.Help =>
                  println("inject node_id msg_type payload")
                  println("help")
                  println("quit")
                case InteractiveCommand.Quit =>
                  stopRequested.trySuccess(())
                  continue = false
                case InteractiveCommand.Invalid(reason) =>
                  println(reason)
                case InteractiveCommand.Inject(nodeId, msgType, payload) =>
                  if !nodeRefs.contains(nodeId) then
                    println(s"Unknown node: $nodeId")
                  else if !inputNodes.contains(nodeId) then
                    println(s"Node $nodeId is not configured as an input node")
                  else
                    nodeRefs(nodeId) ! NodeActor.ExternalInput(msgType, payload)
      }
    }
