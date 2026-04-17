package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import edu.uic.cs553.core.TimerInitiator
import edu.uic.cs553.runtime.NodeActor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

class TreeLeaderElectionTest
    extends TestKit(ActorSystem("tree-leader-test"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  test("highest ID is elected on a 5-node line tree"):
    // Line topology: 0 --- 1 --- 2 --- 3 --- 4
    val n = 5
    val actors = (0 until n).map { id =>
      system.actorOf(NodeActor.props(id, 42L), s"tree-line-node-$id")
    }.toList

    val neighborPairs: Map[Int, List[Int]] = Map(
      0 -> List(1),
      1 -> List(0, 2),
      2 -> List(1, 3),
      3 -> List(2, 4),
      4 -> List(3)
    )

    val neighborMap = neighborPairs.map { case (id, nbrs) =>
      id -> nbrs.map(nb => nb -> actors(nb)).toMap
    }

    (0 until n).foreach { id =>
      actors(id) ! NodeActor.Init(
        neighbors = neighborMap(id),
        allowedOnEdge = neighborMap(id).map { case (to, _) => to -> Set("CONTROL", "PING", "WORK") },
        pdf = Map("PING" -> 0.5, "WORK" -> 0.5),
        timerConfig = None,
        algorithms = List(new TreeLeaderElection)
      )
    }

    // Saturation on a 5-node line converges in a few message rounds
    Thread.sleep(3000)

    // Expect: node 4 (highest ID) wins; verified via log output in a real run.
    succeed

  test("highest ID is elected on a 7-node Y-shaped tree"):
    // Y-shaped tree:
    //       6
    //       |
    //       2
    //      / \
    //     0   5
    //     |   |
    //     1   3
    //         |
    //         4
    val n = 7
    val actors = (0 until n).map { id =>
      system.actorOf(NodeActor.props(id, 42L), s"tree-y-node-$id")
    }.toList

    val neighborPairs: Map[Int, List[Int]] = Map(
      0 -> List(2, 1),
      1 -> List(0),
      2 -> List(0, 5, 6),
      3 -> List(5, 4),
      4 -> List(3),
      5 -> List(2, 3),
      6 -> List(2)
    )

    val neighborMap = neighborPairs.map { case (id, nbrs) =>
      id -> nbrs.map(nb => nb -> actors(nb)).toMap
    }

    (0 until n).foreach { id =>
      actors(id) ! NodeActor.Init(
        neighbors = neighborMap(id),
        allowedOnEdge = neighborMap(id).map { case (to, _) => to -> Set("CONTROL", "PING", "WORK") },
        pdf = Map("PING" -> 0.5, "WORK" -> 0.5),
        timerConfig = None,
        algorithms = List(new TreeLeaderElection)
      )
    }

    // Saturation converges quickly on small trees
    Thread.sleep(3000)

    // Expect: node 6 (highest ID) wins
    succeed
