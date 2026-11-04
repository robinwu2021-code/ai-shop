package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.catalog.SkuComponent;
import ai.neargo.commerce.api.common.ComponentKind;

import java.math.BigInteger;
import java.util.*;

/** Integer allocation v1: componentNo order, then cycle sequence; gifts always have zero basis. */
public final class ComponentAllocation {
    private ComponentAllocation() {}

    public record Cycle(
            int sequence, long gross, long deal, long credit, long cash, long eligibleBasis) {}

    public record Allocation(
            SkuComponent component,
            long gross,
            long deal,
            long credit,
            long cash,
            List<Cycle> cycles) {
        public Allocation {
            cycles = List.copyOf(cycles);
        }
    }

    public static List<Allocation> allocate(
            long deal, long availableCredit, int quantity, List<SkuComponent> components) {
        return allocate(deal, deal, availableCredit, quantity, components);
    }

    /**
     * Allocate deal then discount with identical weights, so no component/cycle has negative
     * discount.
     */
    public static List<Allocation> allocate(
            long gross,
            long deal,
            long availableCredit,
            int quantity,
            List<SkuComponent> components) {
        if (gross < deal || deal < 0 || availableCredit < 0 || quantity < 1 || components.isEmpty())
            throw new IllegalArgumentException("invalid allocation");
        var sorted =
                components.stream()
                        .sorted(Comparator.comparing(SkuComponent::componentNo))
                        .toList();
        var seen = new HashSet<String>();
        var weight = BigInteger.ZERO;
        for (var c : sorted) {
            if (!seen.add(c.componentNo())
                    || c.unitCount() < 1
                    || c.allocationWeight() < 0
                    || (c.gift() && c.allocationWeight() != 0))
                throw new IllegalArgumentException("invalid component");
            int cycles = Math.multiplyExact(c.unitCount(), quantity);
            if (cycles > 10000)
                throw new IllegalArgumentException(
                        "at most 10000 materialized cycles per component");
            weight = weight.add(BigInteger.valueOf(c.allocationWeight()));
        }
        if (deal > 0 && weight.signum() == 0)
            throw new IllegalArgumentException("missing allocation weights");
        long[] amounts = weighted(deal, sorted, weight);
        long[] discounts = weighted(gross - deal, sorted, weight);
        var result = new ArrayList<Allocation>();
        long creditLeft = availableCredit;
        for (int i = 0; i < amounts.length; i++) {
            var c = sorted.get(i);
            long amount = amounts[i];
            long credit =
                    c.kind() == ComponentKind.PLAN && !c.gift() ? Math.min(creditLeft, amount) : 0;
            creditLeft -= credit;
            int units = Math.multiplyExact(quantity, c.unitCount());
            var cycles = new ArrayList<Cycle>();
            for (int n = 0; n < units; n++) {
                long d = portion(amount, units, n), cr = portion(credit, units, n);
                cycles.add(
                        new Cycle(
                                n + 1,
                                Math.addExact(d, portion(discounts[i], units, n)),
                                d,
                                cr,
                                d - cr,
                                c.kind() == ComponentKind.PLAN && !c.gift() ? d : 0));
            }
            result.add(
                    new Allocation(
                            c,
                            Math.addExact(amount, discounts[i]),
                            amount,
                            credit,
                            amount - credit,
                            cycles));
        }
        return List.copyOf(result);
    }

    private static long[] weighted(long total, List<SkuComponent> sorted, BigInteger weight) {
        long[] amounts = new long[sorted.size()];
        long assigned = 0;
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] =
                    weight.signum() == 0
                            ? portion(total, sorted.size(), i)
                            : BigInteger.valueOf(total)
                                    .multiply(BigInteger.valueOf(sorted.get(i).allocationWeight()))
                                    .divide(weight)
                                    .longValueExact();
            assigned = Math.addExact(assigned, amounts[i]);
        }
        for (int i = 0; assigned < total; i++)
            if (sorted.get(i).allocationWeight() > 0) {
                amounts[i]++;
                assigned++;
            }
        return amounts;
    }

    private static long portion(long amount, int units, int n) {
        return amount / units + (n < amount % units ? 1 : 0);
    }
}
