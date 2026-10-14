package dev.skewer.checker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import dev.skewer.checker.Simulated.Serial;
import dev.skewer.checker.Simulated.Version;
import dev.skewer.checker.graph.DependencyGraph;
import dev.skewer.checker.graph.Edge;
import dev.skewer.checker.graph.EdgeType;
import dev.skewer.checker.graph.Tarjan;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** The M3 gate: no false positives, mutations caught, graph algorithms agree with JGraphT. */
class CheckerPropertiesTest {

    @Property(tries = 10_000)
    void serialHistoriesAreAlwaysClean(@ForAll long seed,
                                       @ForAll @IntRange(min = 1, max = 6) int accounts,
                                       @ForAll @IntRange(min = 1, max = 40) int txns) {
        Serial serial = Simulated.serial(new Random(seed), accounts, txns);

        CheckResult r = Checker.check(serial.history());

        assertThat(r.anomalyCount()).as("anomalies in a serial history").isZero();
        assertThat(r.violations()).as("invariant violations in a serial history").isEmpty();
        assertThat(r.instrumentationIssues()).isEmpty();
    }

    /**
     * Change one read-modify-write's read to the version before the one it really saw. It then overwrote a write
     * it never saw: a lost update the checker must flag.
     */
    @Property(tries = 2_000)
    void staleReadMutationIsAlwaysCaught(@ForAll long seed,
                                         @ForAll @IntRange(min = 1, max = 5) int accounts,
                                         @ForAll @IntRange(min = 2, max = 40) int txns) {
        Random rnd = new Random(seed);
        Serial serial = Simulated.serial(rnd, accounts, txns);
        History h = serial.history();

        record Candidate(int txn, int read, Version older) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < h.txns().size(); i++) {
            Txn t = h.txns().get(i);
            Set<Long> written = t.deltas().stream().map(d -> d.account()).collect(Collectors.toSet());
            for (int j = 0; j < t.reads().size(); j++) {
                Read read = t.reads().get(j);
                List<Version> chain = serial.chains().get(read.account());
                int pos = indexOf(chain, read);
                if (written.contains(read.account()) && pos >= 1) {
                    candidates.add(new Candidate(i, j, chain.get(pos - 1)));
                }
            }
        }
        Assume.that(!candidates.isEmpty());
        Candidate c = candidates.get(rnd.nextInt(candidates.size()));

        List<Txn> txnsMutated = new ArrayList<>(h.txns());
        Txn t = txnsMutated.get(c.txn());
        List<Read> reads = new ArrayList<>(t.reads());
        Read original = reads.get(c.read());
        reads.set(c.read(), new Read(original.account(), c.older().writer(), c.older().balance()));
        txnsMutated.set(c.txn(), new Txn(t.id(), t.opId(), t.op(), t.strategy(), t.isolation(), t.outcome(), reads,
                t.deltas(), t.source(), t.committedAt()));
        History mutated = new History(h.meta(), h.accounts(), txnsMutated, h.writes(), h.finalBalances(), h.clientOps());

        CheckResult r = Checker.check(mutated);

        assertThat(r.anomalies().get("G-single")).as("G-single cycles after a stale-read mutation").isPositive();
        assertThat(r.labels()).containsKey("lost update");
    }

    @Property(tries = 1_000)
    void tarjanAgreesWithJGraphT(@ForAll long seed,
                                 @ForAll @IntRange(min = 1, max = 5) int accounts,
                                 @ForAll @IntRange(min = 1, max = 30) int txns,
                                 @ForAll @IntRange(min = 0, max = 60) int reads) {
        History h = Simulated.arbitrary(new Random(seed), accounts, txns, reads);
        DependencyGraph g = DependencyGraph.build(h);

        for (Set<EdgeType> types : List.of(EnumSet.of(EdgeType.WW), EnumSet.of(EdgeType.WW, EdgeType.WR),
                EnumSet.allOf(EdgeType.class))) {
            Tarjan.Components ours = Tarjan.run(g, types);
            Set<Set<Integer>> mine = ours.components().stream()
                    .map(c -> java.util.Arrays.stream(c).boxed().collect(Collectors.toSet()))
                    .collect(Collectors.toSet());
            Graph<Integer, DefaultEdge> jg = jgrapht(g, types);
            Set<Set<Integer>> theirs = new HashSet<>(new KosarajuStrongConnectivityInspector<>(jg).stronglyConnectedSets());
            assertThat(mine).as("SCCs over %s", types).isEqualTo(theirs);
        }
    }

    @Property(tries = 1_000)
    void everyReportedCycleIsRealAndCorrectlyClassified(@ForAll long seed,
                                                        @ForAll @IntRange(min = 1, max = 5) int accounts,
                                                        @ForAll @IntRange(min = 1, max = 30) int txns,
                                                        @ForAll @IntRange(min = 0, max = 60) int reads) {
        History h = Simulated.arbitrary(new Random(seed), accounts, txns, reads);
        DependencyGraph g = DependencyGraph.build(h);
        Classifier.Result result = Classifier.classify(g, Integer.MAX_VALUE);

        for (Anomaly a : result.anomalies()) {
            List<Edge> cycle = a.cycle();
            for (int i = 0; i < cycle.size(); i++) {
                assertThat(cycle.get(i).to()).isEqualTo(cycle.get((i + 1) % cycle.size()).from());
                assertThat(g.out(cycle.get(i).from())).contains(cycle.get(i));
            }
            long rw = cycle.stream().filter(e -> e.type() == EdgeType.RW).count();
            long wr = cycle.stream().filter(e -> e.type() == EdgeType.WR).count();
            switch (a.cls()) {
                case G0 -> assertThat(rw + wr).isZero();
                case G1C -> assertThat(rw).isZero();
                case G_SINGLE -> assertThat(rw).isEqualTo(1);
                case G2_ITEM -> assertThat(rw).isGreaterThanOrEqualTo(2);
            }
        }

        // Completeness against JGraphT's cycle detector, layer by layer.
        boolean wwCycle = new CycleDetector<>(jgrapht(g, EnumSet.of(EdgeType.WW))).detectCycles();
        assertThat(result.counts().get(AnomalyClass.G0) > 0).isEqualTo(wwCycle);
        boolean anyCycle = new CycleDetector<>(jgrapht(g, EnumSet.allOf(EdgeType.class))).detectCycles();
        assertThat(result.anomalies().isEmpty()).isEqualTo(!anyCycle);
    }

    private static Graph<Integer, DefaultEdge> jgrapht(DependencyGraph g, Set<EdgeType> types) {
        Graph<Integer, DefaultEdge> jg = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (int v = 0; v < g.size(); v++) {
            jg.addVertex(v);
        }
        for (int v = 0; v < g.size(); v++) {
            for (Edge e : g.out(v)) {
                if (types.contains(e.type())) {
                    jg.addEdge(e.from(), e.to());
                }
            }
        }
        return jg;
    }

    private static int indexOf(List<Version> chain, Read read) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).writer().equals(read.saw())) {
                return i;
            }
        }
        return -1;
    }
}
