package edu.uic.cs553.runtime

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class NodeActorTest
    extends TestKit(ActorSystem("test"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  test("NodeActor initializes and responds to ExternalInput"):
    val probe = TestProbe()
    val actor = system.actorOf(NodeActor.props(0, 42L))

    actor ! NodeActor.Init(
      neighbors = Map(1 -> probe.ref),
      allowedOnEdge = Map(1 -> Set("WORK")),
      pdf = Map("WORK" -> 1.0),
      timerConfig = None,
      algorithms = Nil
    )

    actor ! NodeActor.ExternalInput("WORK", "test-payload")
    probe.expectMsgType[NodeActor.Envelope](3.seconds)

  test("NodeActor enforces edge label constraints"):
    val probe = TestProbe()
    val actor = system.actorOf(NodeActor.props(1, 42L))

    actor ! NodeActor.Init(
      neighbors = Map(2 -> probe.ref),
      allowedOnEdge = Map(2 -> Set("PING")),
      pdf = Map("WORK" -> 1.0),
      timerConfig = None,
      algorithms = Nil
    )

    // WORK is not allowed on the edge to node 2
    actor ! NodeActor.ExternalInput("WORK", "should-not-forward")
    probe.expectNoMessage(scala.concurrent.duration.DurationInt(1).second)
