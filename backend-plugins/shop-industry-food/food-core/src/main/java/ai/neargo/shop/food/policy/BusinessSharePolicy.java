package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessShareContracts.AllocationSource;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.Division;
import ai.neargo.shop.food.dto.BusinessShareContracts.PersonTotal;
import ai.neargo.shop.food.dto.BusinessShareContracts.ShareCell;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Integer largest remainders conserve original amounts without implying a core financial share
 * reservation.
 */
public final class BusinessSharePolicy {
    private BusinessSharePolicy() {}

    public static Division divide(List<AllocationSource> sources, CreateReq request) {
        try {
            return checkedDivide(sources, request);
        } catch (ArithmeticException overflow) {
            throw bad();
        }
    }

    private static Division checkedDivide(List<AllocationSource> sources, CreateReq request) {
        if (request == null
                || request.mode() == null
                || !Set.of("BY_PERSON", "BY_AMOUNT", "BY_LINE").contains(request.mode())
                || request.people() == null
                || request.people().isEmpty()
                || request.people().size() > 50
                || sources == null
                || sources.isEmpty()
                || sources.size() > 1000) throw bad();
        var weights = new TreeMap<String, Long>();
        for (var person : request.people()) {
            if (person == null) throw bad();
            key(person.personNo());
            long value = 1;
            if ("BY_AMOUNT".equals(request.mode())) {
                if (person.amountMinor() == null || person.amountMinor() < 0) throw bad();
                value = person.amountMinor();
            } else if (person.amountMinor() != null) throw bad();
            if (weights.put(person.personNo(), value) != null) throw bad();
        }
        if (sources.stream().anyMatch(s -> s == null || s.allocationNo() == null)) throw bad();
        var sorted =
                sources.stream()
                        .sorted(Comparator.comparing(AllocationSource::allocationNo))
                        .toList();
        var allocations = new HashSet<String>();
        var sourceLines = new HashSet<String>();
        long total = 0;
        for (var source : sorted) {
            if (source == null
                    || source.businessAmountMinor() < 0
                    || source.allocationNo() == null
                    || source.foodLineNo() == null
                    || !allocations.add(source.allocationNo())) throw bad();
            total = Math.addExact(total, source.businessAmountMinor());
            sourceLines.add(source.foodLineNo());
        }
        var lineWeights = new HashMap<String, Map<String, Long>>();
        if ("BY_LINE".equals(request.mode())) {
            if (request.lineWeights() == null
                    || request.lineWeights().isEmpty()
                    || request.lineWeights().size() > 1000) throw bad();
            for (var line : request.lineWeights()) {
                if (line == null
                        || !sourceLines.contains(line.foodLineNo())
                        || !weights.containsKey(line.personNo())
                        || line.weight() == null
                        || line.weight() < 1
                        || line.weight() > 1_000_000_000L) throw bad();
                if (lineWeights
                                .computeIfAbsent(line.foodLineNo(), ignored -> new TreeMap<>())
                                .put(line.personNo(), line.weight())
                        != null) throw bad();
            }
            if (!sourceLines.equals(lineWeights.keySet())) throw bad();
        } else if (request.lineWeights() != null && !request.lineWeights().isEmpty()) throw bad();
        Map<String, Long> remaining;
        if ("BY_AMOUNT".equals(request.mode())) {
            long supplied = 0;
            for (var value : weights.values()) supplied = Math.addExact(supplied, value);
            if (supplied != total) throw bad();
            remaining = new TreeMap<>(weights);
        } else remaining = new TreeMap<>(largestRemainder(total, weights));
        var lineRemaining = new HashMap<String, Map<String, Long>>();
        if ("BY_LINE".equals(request.mode())) {
            var lineTotals = new HashMap<String, Long>();
            for (var source : sorted)
                lineTotals.put(
                        source.foodLineNo(),
                        Math.addExact(
                                lineTotals.getOrDefault(source.foodLineNo(), 0L),
                                source.businessAmountMinor()));
            for (var line : lineTotals.entrySet())
                lineRemaining.put(
                        line.getKey(),
                        new TreeMap<>(
                                largestRemainder(line.getValue(), lineWeights.get(line.getKey()))));
        }
        var cells = new ArrayList<ShareCell>();
        var actual = new TreeMap<String, Long>();
        weights.keySet().forEach(p -> actual.put(p, 0L));
        for (var source : sorted) {
            var rowRemaining =
                    "BY_LINE".equals(request.mode())
                            ? lineRemaining.get(source.foodLineNo())
                            : remaining;
            var row = largestRemainder(source.businessAmountMinor(), rowRemaining);
            for (var part : row.entrySet()) {
                cells.add(
                        new ShareCell(
                                source.allocationNo(),
                                source.foodLineNo(),
                                source.orderItemId(),
                                part.getKey(),
                                part.getValue()));
                actual.put(
                        part.getKey(), Math.addExact(actual.get(part.getKey()), part.getValue()));
                rowRemaining.put(
                        part.getKey(),
                        Math.subtractExact(rowRemaining.get(part.getKey()), part.getValue()));
            }
        }
        if (!"BY_LINE".equals(request.mode()) && remaining.values().stream().anyMatch(v -> v != 0))
            throw bad();
        if ("BY_LINE".equals(request.mode())
                && lineRemaining.values().stream()
                        .flatMap(row -> row.values().stream())
                        .anyMatch(v -> v != 0)) throw bad();
        long actualTotal = 0;
        for (var value : actual.values()) actualTotal = Math.addExact(actualTotal, value);
        if (actualTotal != total) throw bad();
        return new Division(
                actual.entrySet().stream()
                        .map(e -> new PersonTotal(e.getKey(), e.getValue()))
                        .toList(),
                cells);
    }

    /**
     * Ties are ordered by stable person identity; multiplication and denominator sums cannot
     * overflow long.
     */
    public static Map<String, Long> largestRemainder(long total, Map<String, Long> weights) {
        if (total < 0 || weights == null || weights.isEmpty() || weights.size() > 50) throw bad();
        var sorted = new TreeMap<>(weights);
        BigInteger denominator = BigInteger.ZERO;
        for (var entry : sorted.entrySet()) {
            key(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 0) throw bad();
            denominator = denominator.add(BigInteger.valueOf(entry.getValue()));
        }
        if (total > 0 && denominator.signum() == 0) throw bad();
        var results = new LinkedHashMap<String, Long>();
        var remainders = new ArrayList<Remainder>();
        long floorTotal = 0;
        for (var entry : sorted.entrySet()) {
            BigInteger[] part =
                    denominator.signum() == 0
                            ? new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO}
                            : BigInteger.valueOf(total)
                                    .multiply(BigInteger.valueOf(entry.getValue()))
                                    .divideAndRemainder(denominator);
            long floor = part[0].longValueExact();
            results.put(entry.getKey(), floor);
            floorTotal = Math.addExact(floorTotal, floor);
            remainders.add(new Remainder(entry.getKey(), part[1]));
        }
        remainders.sort(
                Comparator.comparing(Remainder::remainder)
                        .reversed()
                        .thenComparing(Remainder::person));
        long leftover = total - floorTotal;
        if (leftover > remainders.size()) throw bad();
        for (int i = 0; i < leftover; i++) {
            String person = remainders.get(i).person();
            results.put(person, results.get(person) + 1);
        }
        return Collections.unmodifiableMap(results);
    }

    private record Remainder(String person, BigInteger remainder) {}

    private static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,32}")) throw bad();
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }
}
