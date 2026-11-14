package ai.neargo.commerce.core.mall;

import java.util.*;
import java.util.regex.Pattern;

/** Core-owned v1 authority. Unknown/legacy or noncanonical encodings require manual review. */
public record RefundPolicy(UnusedDigital unusedDigital) {
    public enum UnusedDigital {
        FULL_ORIGINAL_ALLOCATION,
        MANUAL_ONLY
    }

    private static final RefundPolicy MANUAL = new RefundPolicy(UnusedDigital.MANUAL_ONLY);
    private static final Pattern FIELD =
            Pattern.compile("\\s*\"([A-Za-z]+)\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"\\s*");

    public boolean automaticUnused() {
        return unusedDigital == UnusedDigital.FULL_ORIGINAL_ALLOCATION;
    }

    public static RefundPolicy parse(String snapshot) {
        if (snapshot == null) return MANUAL;
        String raw = snapshot.trim();
        if (!raw.startsWith("{") || !raw.endsWith("}")) return MANUAL;
        var fields = new HashMap<String, String>();
        for (String field : raw.substring(1, raw.length() - 1).split(",", -1)) {
            var match = FIELD.matcher(field);
            if (!match.matches() || fields.put(match.group(1), match.group(2)) != null)
                return MANUAL;
        }
        if (!fields.keySet().equals(Set.of("schema", "unusedDigital", "usedDigital"))
                || !"commerce-refund-v1".equals(fields.get("schema"))
                || !"MANUAL_ONLY".equals(fields.get("usedDigital"))) return MANUAL;
        try {
            return new RefundPolicy(UnusedDigital.valueOf(fields.get("unusedDigital")));
        } catch (RuntimeException unknown) {
            return MANUAL;
        }
    }
}
