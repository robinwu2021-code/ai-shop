package ai.neargo.shop.message.notify;

import ai.neargo.shop.event.outbox.DeliveryResult;
import java.util.Map;
import java.util.TreeMap;

/** Scoped, bounded channel evidence. Persistent notify numbers link to the existing action ledger. */
public final class OutboxChannelOutcomes {
    private static final ThreadLocal<OutboxChannelOutcomes> CURRENT = new ThreadLocal<>();
    private final Map<String, Integer> failed = new TreeMap<>();
    private final StringBuilder references = new StringBuilder();
    private int success;
    private boolean missingReceipt;
    private OutboxChannelOutcomes() { }
    public static void recorded(String channel, String status, String notifyNo) {
        var scope = CURRENT.get();
        if (scope == null) { return; }
        if ("FAILED".equals(status)) { scope.failed.merge(channel, 1, Integer::sum); }
        else { scope.success++; }
        if (scope.references.length() < 220) {
            if (!scope.references.isEmpty()) { scope.references.append(','); }
            scope.references.append(notifyNo);
        }
    }
    public static void unrecorded() {
        var scope = CURRENT.get();
        if (scope != null) { scope.missingReceipt = true; }
    }
    public static void failure(String channel) {
        var scope = CURRENT.get();
        if (scope != null) { scope.failed.putIfAbsent(channel, 1); }
    }
    public static DeliveryResult capture(Runnable action) {
        var previous = CURRENT.get();
        var scope = new OutboxChannelOutcomes();
        CURRENT.set(scope);
        try {
            action.run();
            String detail = "sent=" + scope.success + ";failed=" + scope.failed.values().stream()
                    .mapToInt(Integer::intValue).sum() + ";channels=" + scope.failed + ";refs=" + scope.references;
            if (scope.missingReceipt) { return DeliveryResult.unknown("CHANNEL_RECEIPT_MISSING;" + detail); }
            return scope.failed.isEmpty() ? DeliveryResult.sent() : DeliveryResult.warning(detail);
        } finally {
            if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
        }
    }
}
