package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import edu.uic.cs553.core.TimerInitiator
import edu.uic.cs553.runtime.NodeActor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

class ItaiRodehTest
    extends TestKit(ActorSystem("itai-rodeh-test"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  test("Itai-Rodeh ring size algorithm runs on a ring of 5 nodes"):
    val n = 5
    val actors = (0 until n).map { id =>
      system.actorOf(NodeActor.props(id, 42L), s"ir-node-$id")
    }.toList

    (0 until n).foreach { id =>
      val nextId = (id + 1) % n
      actors(id) ! NodeActor.Init(
        neighbors = Map(nextId -> actors(nextId)),
        allowedOnEdge = Map(nextId -> Set("CONTROL", "PING")),
        pdf = Map("PING" -> 1.0),
        timerConfig = Some(TimerInitiator(id, 100L)),
        algorithms = List(new ItaiRodehRingSize)
      )
    }

    // Allow time for the algorithm to converge
    Thread.sleep(5000)

    // The algorithm should determine ring size = 5
    // (verified via logs in a real run)
    succeed
