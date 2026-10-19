package ai.neargo.shop.pay.storedvalue.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Balanced management books express the customer claim and merchant obligation, never platform bank cash. */
public final class StoredValuePostingPolicy {
    private StoredValuePostingPolicy() { }
    public record Line(String pool, String book, String side, long amountMinor) { }
    public static List<Line> plan(long principalDelta, long bonusDelta) {
        var lines = new ArrayList<Line>();
        add(lines, "PRINCIPAL", principalDelta); add(lines, "BONUS", bonusDelta);
        requireBalanced(lines);
        return List.copyOf(lines);
    }
    private static void add(List<Line> lines, String pool, long delta) {
        if (delta == 0) return;
        long amount = delta > 0 ? delta : Math.negateExact(delta);
        lines.add(new Line(pool, "CUSTOMER_CLAIM", delta > 0 ? "DEBIT" : "CREDIT", amount));
        lines.add(new Line(pool, "MERCHANT_OBLIGATION", delta > 0 ? "CREDIT" : "DEBIT", amount));
    }
    public static void requireBalanced(List<Line> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty stored-value posting");
        Map<String, Long> totals = new HashMap<>();
        for (var line : lines) {
            if (line.amountMinor() <= 0 || !List.of("PRINCIPAL", "BONUS").contains(line.pool())
                    || !List.of("DEBIT", "CREDIT").contains(line.side())
                    || !List.of("CUSTOMER_CLAIM", "MERCHANT_OBLIGATION").contains(line.book())) {
                throw new IllegalArgumentException("Invalid management posting");
            }
            long signed = line.side().equals("DEBIT") ? line.amountMinor() : Math.negateExact(line.amountMinor());
            totals.merge(line.pool(), signed, Math::addExact);
        }
        if (totals.values().stream().anyMatch(value -> value != 0)) {
            throw new IllegalStateException("Stored-value journal is not balanced per pool");
        }
    }
}
