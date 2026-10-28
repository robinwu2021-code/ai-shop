package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.SelectionRules;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Exact owner quantity proof, separate from prices, defaults, stock and channel authorization. */
public final class SelectionFeasibility {
    private SelectionFeasibility() { }
    private static final int GROUP_LIMIT = 100000;

    /** Proven absence and exhausted search have different business meanings and must never be conflated. */
    public enum Status { PROVEN, IMPOSSIBLE, UNREACHABLE, UNPROVEN }

    /**
     * The two counts are proven minima for composing independent groups under a line-wide count limit.
     * maxForcedMinSelections is the largest minimum when any one visible choice is forced.
     * Counts are absent unless all choices were proven; the first unreachable key is diagnostic.
     */
    public record Result(Status status, List<String> unreachableItemNos, long visitedNodes,
            Integer minimumSelections, Integer maxForcedMinSelections) {
        public Result { unreachableItemNos = List.copyOf(unreachableItemNos); }
    }

    /** Mutable, capture-local and not thread-safe; reuse the same budget across every menu in one catalog. */
    public static final class Budget {
        private final long limit;
        private long used;

        /** A bounded custom limit allows callers to spend less, never more than the catalog ceiling. */
        public Budget(long limit) {
            if (limit < 1 || limit > 1000000) throw BizException.of(ErrorCode.BAD_REQUEST);
            this.limit = limit;
        }

        /** The same instance spans every source and group in one capture. */
        public static Budget catalog() { return new Budget(1000000); }
        /** Counts actual search states, including unsuccessful branches, for capture diagnostics. */
        public long usedNodes() { return used; }
    }

    record Choice(String id, BigDecimal min, BigDecimal max) { }

    static Result prove(BigDecimal min, BigDecimal max, List<Choice> choices, SelectionRules rules,
            Budget budget, boolean everyChoice) {
        if (budget == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var solver = new Solver(min, max, choices, rules, budget);
        long before = budget.used;
        try {
            var witness = solver.search(new BitSet(), new BitSet(), CartInputPolicy.MAX_SELECTIONS);
            if (witness == null) return result(Status.IMPOSSIBLE, List.of(), budget, before);
            if (!everyChoice) return result(Status.PROVEN, List.of(), budget, before);
            var minimumWitness = solver.minimize(new BitSet(), witness);
            int minimum = minimumWitness.cardinality(), worst = minimum;
            for (int i = 0; i < choices.size(); i++) {
                if (solver.upper[i].signum() == 0 || minimumWitness.get(i)) continue;
                var forced = new BitSet(); forced.set(i);
                var reachable = solver.search(forced, new BitSet(), CartInputPolicy.MAX_SELECTIONS);
                if (reachable == null)
                    return result(Status.UNREACHABLE, List.of(choices.get(i).id()), budget, before);
                worst = Math.max(worst, solver.minimize(forced, reachable).cardinality());
            }
            return new Result(Status.PROVEN, List.of(), budget.used - before, minimum, worst);
        } catch (Exhausted limit) {
            return result(Status.UNPROVEN, List.of(), budget, before);
        }
    }

    private static Result result(Status status, List<String> ids, Budget budget, long before) {
        return new Result(status, ids, budget.used - before, null, null);
    }

    private static BigInteger units(BigDecimal value) { return value.movePointRight(6).toBigIntegerExact(); }

    private static final class Exhausted extends RuntimeException {
        Exhausted() { super(null, null, false, false); }
    }

    private static final class Solver {
        final BigInteger minimum;
        final BigInteger maximum;
        final BigInteger[] lower;
        final BigInteger[] upper;
        final BitSet required = new BitSet();
        final List<BitSet> dependencies = new ArrayList<>();
        final List<BitSet> mutex = new ArrayList<>();
        final Budget budget;
        int visited;

        Solver(BigDecimal min, BigDecimal max, List<Choice> choices, SelectionRules rules, Budget budget) {
            this.minimum = units(min); this.maximum = units(max); this.budget = budget;
            this.lower = new BigInteger[choices.size()]; this.upper = new BigInteger[choices.size()];
            var index = new HashMap<String, Integer>();
            for (int i = 0; i < choices.size(); i++) {
                var c = choices.get(i); index.put(c.id(), i);
                lower[i] = units(c.min()).max(BigInteger.ONE);
                upper[i] = units(c.max().min(CartInputPolicy.MAX_SELECTION_QUANTITY));
                if (c.min().signum() > 0) required.set(i);
                dependencies.add(new BitSet());
            }
            for (var d : rules.requires()) for (String id : d.requiresItemNos())
                dependencies.get(index.get(d.optionItemNo())).set(index.get(id));
            for (var set : rules.mutexSets()) {
                var bits = new BitSet(); set.forEach(id -> bits.set(index.get(id))); mutex.add(bits);
            }
        }

        BitSet minimize(BitSet forced, BitSet witness) {
            int low = forced.cardinality(), high = witness.cardinality();
            while (low < high) {
                int middle = low + (high - low) / 2;
                var candidate = search((BitSet) forced.clone(), new BitSet(), middle);
                if (candidate == null) low = middle + 1;
                else { witness = candidate; high = candidate.cardinality(); }
            }
            return witness;
        }

        BitSet search(BitSet selected, BitSet excluded, int countLimit) {
            if (visited >= GROUP_LIMIT || budget.used >= budget.limit) throw new Exhausted();
            visited++; budget.used++;
            selected.or(required);
            boolean changed;
            do {
                int count = selected.cardinality();
                for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1))
                    selected.or(dependencies.get(i));
                changed = count != selected.cardinality();
            } while (changed);
            if (selected.intersects(excluded) || selected.cardinality() > countLimit) return null;
            for (var set : mutex) {
                var picked = (BitSet) selected.clone(); picked.and(set);
                if (picked.cardinality() > 1) return null;
                if (!picked.isEmpty()) {
                    var blocked = (BitSet) set.clone(); blocked.andNot(picked); excluded.or(blocked);
                }
            }
            BigInteger lo = BigInteger.ZERO, hi = BigInteger.ZERO;
            var optional = new ArrayList<BigInteger>();
            for (int i = 0; i < upper.length; i++) {
                if (!selected.get(i) && !excluded.get(i)) optional.add(upper[i]);
                if (selected.get(i)) {
                    if (lower[i].compareTo(upper[i]) > 0) return null;
                    lo = lo.add(lower[i]); hi = hi.add(upper[i]);
                }
            }
            optional.sort(Comparator.reverseOrder());
            BigInteger possible = hi;
            for (int i = 0; i < Math.min(optional.size(), countLimit - selected.cardinality()); i++)
                possible = possible.add(optional.get(i));
            if (lo.compareTo(maximum) > 0 || possible.compareTo(minimum) < 0) return null;
            if (lo.max(minimum).compareTo(hi.min(maximum)) <= 0) return selected;
            int next = -1;
            for (int i = 0; i < upper.length; i++) if (!selected.get(i) && !excluded.get(i)
                    && upper[i].signum() > 0) { next = i; break; }
            if (next < 0) return null;
            var include = (BitSet) selected.clone(); include.set(next);
            var found = search(include, (BitSet) excluded.clone(), countLimit);
            if (found != null) return found;
            var omit = (BitSet) excluded.clone(); omit.set(next);
            return search((BitSet) selected.clone(), omit, countLimit);
        }
    }
}
