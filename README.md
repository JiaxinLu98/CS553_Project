# NetGameSimAkka

CS 553 course project: a configurable Akka simulator that turns
NetGameSim-generated graphs into a running message-passing system and
implements two assigned distributed algorithms on top of the same runtime.

## Demo video

A short walkthrough that shows graph generation, a live run for each
implemented algorithm, and their final log outcomes:

https://www.youtube.com/watch?v=ybtcoRFXQy0

## Assigned algorithms

Per `extraRule1.txt`, UIN = 663862147 selects:

| Index | Value | Algorithm | Topology |
|-------|-------|-----------|----------|
| 1 + (UIN mod 23) | 15 | **Itai-Rodeh ring size** | ring |
| 1 + (floor(UIN/23) mod 23) | 21 | **Leader election in trees** | tree |

A third algorithm, **Bully**, is included as a reference implementation on a
complete graph; it is not part of the assigned pair but shares the same
runtime and serves as a cross-check for the plug-in framework.

| Algorithm class | Required topology | Why |
|---|---|---|
| `ItaiRodehRingSize` | ring | Anonymous ring size detection needs a cycle with oriented single-successor links |
| `TreeLeaderElection` | tree | The saturation protocol assumes a connected acyclic undirected graph |
| `BullyAlgorithm` | complete | Every process must be able to challenge every higher-id process directly |

## Requirements

- **JDK 17** (Temurin or equivalent). The project is configured for `JDK_17`
  in `.idea/misc.xml`.
- **sbt 1.9.7** (IntelliJ bundles this; standalone installs at
  `https://www.scala-sbt.org/` also work). sbt pulls Scala 3.3.1 and all
  library dependencies on first sync.
- **IntelliJ IDEA with the Scala plugin** (optional but recommended; enables
  sbt shell and Akka-aware navigation). `.idea/sbt.xml` already declares the
  four subprojects.

## Install on a clean machine

1. Install JDK 17 and make sure `java -version` reports `17.x` on the
   command line.
2. Clone the repository and open its root directory in IntelliJ. IntelliJ
   prompts to import the sbt project; accept and wait for the first sync
   (downloads Akka 2.8.5, ScalaTest 3.2.17, circe 0.14.6, etc.).
3. Open the **sbt shell** (`View → Tool Windows → sbt shell`).

## Build and test

Inside the sbt shell:

```
compile
test
```

`test` aggregates 20 ScalaTest cases across four subprojects
(`sim-core` 14, `sim-runtime-akka` 2, `sim-algorithms` 4). If the shell is
scoped to a subproject instead of the root, run `project root` first.

## Two-step experiment workflow

Each experiment runs in two steps: `generate` produces an enriched graph
artifact, `run` executes the Akka simulation on that artifact.

```
simCli/runMain edu.uic.cs553.SimMain generate --config <path-to-conf> --out <out-dir>
simCli/runMain edu.uic.cs553.SimMain run      --graph <out-dir>/graph.json [--inject-file <file>] [--interactive] [--run-ms <ms>]
```

`graph.json` is the enriched artifact (nodes, edges, PDFs, edge labels,
algorithm selection). `topology.json` is a normalized raw-topology
companion file, useful for debugging and reproducibility.

Supported interactive commands in `--interactive` mode:

- `inject <node_id> <msg_type> <payload>`
- `help`
- `quit`

## Example runs

### Bully on a complete graph

```
simCli/runMain edu.uic.cs553.SimMain generate --config conf/bully-experiment.conf --out outputs/bully-run
simCli/runMain edu.uic.cs553.SimMain run      --graph outputs/bully-run/graph.json --inject-file conf/bully-injections.txt
```

Expect the highest-id node to declare itself coordinator after the election
rounds.

### Itai-Rodeh ring-size detection

```
simCli/runMain edu.uic.cs553.SimMain generate --config conf/itai-rodeh-experiment.conf --out outputs/itai-rodeh-run
simCli/runMain edu.uic.cs553.SimMain run      --graph outputs/itai-rodeh-run/graph.json --inject-file conf/itai-rodeh-injections.txt
```

Every node eventually logs an estimated ring size; in a synchronous,
asymmetric ring the estimate converges to the true `n`.

### Leader election in trees (saturation)

```
simCli/runMain edu.uic.cs553.SimMain generate --config conf/tree-leader-small.conf --out outputs/tree-run1
simCli/runMain edu.uic.cs553.SimMain run      --graph outputs/tree-run1/graph.json
```

`tree-leader-small.conf` produces a 7-node random recursive tree (seed
`20260417`, 6 undirected edges = 12 directed entries in `topology.json`,
exactly `n-1`). Expect one log line of the form
`Node N elected itself LEADER` followed by `n-1` lines of
`Node M acknowledges LEADER=N`, all agreeing on the same leader id.
`conf/tree-leader-experiment.conf` runs the same algorithm on a 15-node
tree for a longer horizon.

## Algorithm notes

### Leader election in trees

Implemented in `sim-algorithms/.../TreeLeaderElection.scala` using the
standard *saturation* (echo-based) algorithm from Tel,
*Introduction to Distributed Algorithms*. Assumptions:

- The underlying graph is an undirected tree (connected, acyclic,
  `n-1` edges). The `tree` topology generator in
  `GraphLoader.generateTreeRaw` builds a random recursive tree: for each
  new node `i ∈ [1, n)` it picks a parent uniformly from `[0, i)` and
  installs a bidirectional edge. This construction guarantees connectivity
  and acyclicity by induction on `i`.
- Each node knows its own unique integer id and its direct neighbors.
- Messages are asynchronous but per-edge FIFO (matched by Akka's
  per-destination ordering).

Protocol (all handlers are idempotent via the `decided` latch):

1. Leaves send `Vote(selfId, self)` to their sole neighbor at start.
2. An internal node that has received `Vote` from all but one neighbor
   forwards `Vote(bestSeen, self)` to the remaining silent neighbor,
   remembering `bestEdge` — the neighbor who delivered the current best
   candidate.
3. A node is *saturated* once it has received `Vote` from every neighbor.
   If its own id equals the best seen, it declares itself leader; otherwise
   it forwards a `Search(bestId)` message along `bestEdge` to find the
   actual owner of that id.
4. The leader broadcasts `Leader(self)` down the tree; each recipient
   records the decision and forwards to all neighbors except the sender,
   giving `O(n)` messages in total.

### Itai-Rodeh ring size

See `sim-algorithms/.../ItaiRodehRingSize.scala` and
`conf/itai-rodeh-*.conf`. The algorithm runs on the `ring` topology
produced by `GraphLoader.generateRingRaw`.

### Bully (reference)

See `sim-algorithms/.../BullyAlgorithm.scala` and `conf/bully-*.conf`.
Runs on the `complete` topology produced by
`GraphLoader.generateCompleteRaw`.

## Repository layout

```
sim-core/            graph model, edge labels, node PDFs, serialization, topology generators
sim-runtime-akka/    NodeActor, NodeContext, DistributedAlgorithm trait, SimRunner
sim-algorithms/      BullyAlgorithm, ItaiRodehRingSize, TreeLeaderElection
sim-cli/             SimMain (generate / run entry points), application.conf
conf/                experiment configuration files and injection scripts
docs/                design report and experiment notes
netgamesim/          upstream NetGameSim submodule (graph generator)
```

## Determinism

All generators take a `seed` from the configuration file. `NodeActor`
derives per-node RNGs as `seed + nodeId` so repeated runs with the same
configuration produce identical traffic under a single-threaded
dispatcher; under the default multi-threaded dispatcher message
interleaving may vary but algorithm outcomes (leader id, detected ring
size, etc.) are stable.
