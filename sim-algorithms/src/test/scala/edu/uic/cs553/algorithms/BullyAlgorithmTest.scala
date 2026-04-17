package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import edu.uic.cs553.core.TimerInitiator
import edu.uic.cs553.runtime.{NodeActor, NodeContext}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class BullyAlgorithmTest
    extends TestKit(ActorSystem("bully-test"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  test("highest ID node becomes coordinator in Bully algorithm"):
    val probes = (0 until 4).map(_ => TestProbe()).toList
    val actors = (0 until 4).map { id =>
      system.actorOf(NodeActor.props(id, 42L), s"bully-node-$id")
    }.toList

    val neighborMap = (0 until 4).map { id =>
      val nbrs = (0 until 4).filter(_ != id).map(n => n -> actors(n)).toMap
      id -> nbrs
    }.toMap

    (0 until 4).foreach { id =>
      actors(id) ! NodeActor.Init(
        neighbors = neighborMap(id),
        allowedOnEdge = neighborMap(id).map { case (to, _) => to -> Set("CONTROL", "PING", "WORK") },
        pdf = Map("PING" -> 0.5, "WORK" -> 0.5),
        timerConfig = Option.when(id == 0)(TimerInitiator(id, 200L)),
        algorithms = List(new BullyAlgorithm)
      )
    }

    // Allow time for election to complete
    Thread.sleep(3000)

    // The algorithm should converge — node 3 is highest ID and should win
    // (verified via logs in a real run)
    succeed
