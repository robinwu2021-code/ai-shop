package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Scoped capacity allocation; frozen money splits must never be recalculated on commit or release. */
@Component
public class StoredValueAllocationPolicy {
    public record LotBalance(String lotNo, long principalMinor, long bonusMinor, RedemptionScope scope,
                             Instant creditedAt, Long originalPrincipalMinor, Long originalBonusMinor) {
        public LotBalance(String lotNo,long principalMinor,long bonusMinor,RedemptionScope scope,Instant creditedAt){this(lotNo,principalMinor,bonusMinor,scope,creditedAt,null,null);}
        public LotBalance {
            ContractChecks.require((originalPrincipalMinor==null)==(originalBonusMinor==null));
            if(originalPrincipalMinor!=null){ContractChecks.require(originalPrincipalMinor>0&&originalBonusMinor>=0&&principalMinor<=originalPrincipalMinor&&bonusMinor<=originalBonusMinor);ContractChecks.addAmounts(originalPrincipalMinor,originalBonusMinor);}
            ContractChecks.id(lotNo); ContractChecks.addAmounts(principalMinor, bonusMinor);
            ContractChecks.present(scope); ContractChecks.present(scope.refs()); ContractChecks.present(creditedAt);
            ContractChecks.require(Set.of("ALL", "STORES", "GOODS", "CATEGORIES").contains(scope.mode()));
            ContractChecks.require("ALL".equals(scope.mode()) ? scope.refs().isEmpty() : !scope.refs().isEmpty());
            scope.refs().forEach(ContractChecks::id);
        }
    }
    public record ItemAllocation(String orderLineNo, long amountMinor) {
        public ItemAllocation { ContractChecks.id(orderLineNo); ContractChecks.require(amountMinor > 0); }
    }
    public record Allocation(String lotNo, long principalMinor, long bonusMinor, List<ItemAllocation> items) {
        public Allocation { items = List.copyOf(items); }
    }
    /** A partial plan is only a quote: the writer requires exactly the full authorized service amount. */
    public record Plan(long amountMinor, List<Allocation> allocations) {
        public Plan { allocations = List.copyOf(allocations); }
    }

    public Plan allocate(List<LotBalance> available, UseSourceFact source) {
        var lots = available.stream().sorted(Comparator.comparing(LotBalance::creditedAt)
                .thenComparing(LotBalance::lotNo)).toList();
        var items = source.items();
        ContractChecks.require(lots.stream().map(LotBalance::lotNo).distinct().count() == lots.size());
        ContractChecks.require(items.stream().map(UseItem::orderLineNo).distinct().count() == items.size());
        long requested = 0;
        for (var item : items) requested = ContractChecks.addAmounts(requested, item.amountMinor());
        int sink = 1 + lots.size() + items.size();
        var graph = new Graph(sink + 1);
        var assignments = new Edge[lots.size()][items.size()];
        for (int i = 0; i < lots.size(); i++) {
            var lot = lots.get(i); long capacity = Math.addExact(lot.principalMinor(), lot.bonusMinor());
            graph.add(0, i + 1, capacity);
            for (int j = 0; j < items.size(); j++) {
                if (eligible(lot.scope(), source, items.get(j))) {
                    assignments[i][j] = graph.add(i + 1, 1 + lots.size() + j,
                            Math.min(capacity, items.get(j).amountMinor()));
                }
            }
        }
        for (int j = 0; j < items.size(); j++) {
            graph.add(1 + lots.size() + j, sink, items.get(j).amountMinor());
        }
        long paid = graph.fill(0, sink);
        if (paid > requested) throw new IllegalStateException("Allocation exceeds the original service amount");
        var allocations = new ArrayList<Allocation>();
        for (int i = 0; i < lots.size(); i++) {
            var details = new ArrayList<ItemAllocation>(); long used = 0;
            for (int j = 0; j < items.size(); j++) {
                var edge = assignments[i][j];
                long applied = edge == null ? 0 : edge.initial - edge.remaining;
                if (applied > 0) {
                    details.add(new ItemAllocation(items.get(j).orderLineNo(), applied));
                    used = Math.addExact(used, applied);
                }
            }
            if (used == 0) continue;
            var lot = lots.get(i);
            long basisBonus=lot.originalBonusMinor()==null?lot.bonusMinor():lot.originalBonusMinor();
            long basisTotal=lot.originalPrincipalMinor()==null?Math.addExact(lot.principalMinor(),lot.bonusMinor()):Math.addExact(lot.originalPrincipalMinor(),lot.originalBonusMinor());
            long bonus=BigInteger.valueOf(used).multiply(BigInteger.valueOf(basisBonus)).divide(BigInteger.valueOf(basisTotal)).longValueExact();
            // Holds and rounding may exhaust one component. The final allocation consumes the exact residue.
            bonus=Math.max(Math.max(0,used-lot.principalMinor()),Math.min(lot.bonusMinor(),bonus));
            allocations.add(new Allocation(lot.lotNo(), used - bonus, bonus, details));
        }
        return new Plan(paid, allocations);
    }
    private boolean eligible(RedemptionScope scope, UseSourceFact source, UseItem item) {
        return switch (scope.mode()) {
            case "ALL" -> true;
            case "STORES" -> scope.refs().contains(source.storeNo());
            case "GOODS" -> scope.refs().contains(item.itemNo());
            case "CATEGORIES" -> source.categoryRefsByLine().getOrDefault(item.orderLineNo(), List.of()).stream()
                    .anyMatch(scope.refs()::contains);
            default -> false;
        };
    }
    private static final class Edge {
        private final int to;
        private final int reverse;
        private final long initial;
        private long remaining;
        private Edge(int to, int reverse, long capacity) {
            this.to = to; this.reverse = reverse; this.initial = capacity; this.remaining = capacity;
        }
    }
    /** Reverse edges can move an old generic allocation to another line, freeing a restricted line for a new lot. */
    private static final class Graph {
        private final List<List<Edge>> edges;
        private Graph(int size) {
            edges = new ArrayList<>(size);
            for (int i = 0; i < size; i++) edges.add(new ArrayList<>());
        }
        private Edge add(int from, int to, long capacity) {
            var forward = new Edge(to, edges.get(to).size(), capacity);
            var reverse = new Edge(from, edges.get(from).size(), 0);
            edges.get(from).add(forward); edges.get(to).add(reverse); return forward;
        }
        private long fill(int start, int sink) {
            long result = 0;
            while (true) {
                var previous = new int[edges.size()]; Arrays.fill(previous, -1); previous[start] = start;
                var path = new Edge[edges.size()]; var queue = new ArrayDeque<Integer>(); queue.add(start);
                while (!queue.isEmpty() && previous[sink] == -1) {
                    int from = queue.removeFirst();
                    for (var edge : edges.get(from)) {
                        if (edge.remaining <= 0 || previous[edge.to] != -1) continue;
                        previous[edge.to] = from; path[edge.to] = edge; queue.addLast(edge.to);
                    }
                }
                if (previous[sink] == -1) return result;
                long amount = Long.MAX_VALUE;
                for (int at = sink; at != start; at = previous[at]) amount = Math.min(amount, path[at].remaining);
                for (int at = sink; at != start; at = previous[at]) {
                    var edge = path[at]; edge.remaining -= amount;
                    var reverse = edges.get(edge.to).get(edge.reverse);
                    reverse.remaining = Math.addExact(reverse.remaining, amount);
                }
                result = Math.addExact(result, amount);
            }
        }
    }
}
