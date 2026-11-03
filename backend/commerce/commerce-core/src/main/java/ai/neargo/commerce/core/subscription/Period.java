package ai.neargo.commerce.core.subscription;

import java.util.Map;

/** One immutable parent source. ends maps each entitlement code to its business end identity. */
public record Period(
        String no,
        String origin,
        String sourceType,
        String planNo,
        String planVersion,
        String tier,
        long delivered,
        long start,
        Long duration,
        Map<String, String> ends,
        long cash,
        long credit,
        String currency,
        String policyVersion,
        boolean gift) {
    public Period {
        if (policyVersion == null || policyVersion.isBlank())
            throw new IllegalStateException("missing source policy evidence");
        ends = Map.copyOf(ends);
    }

    public Long end() {
        return duration == null ? null : Math.addExact(start, duration);
    }

    public boolean fixed() {
        return planNo != null;
    }

    public long basis() {
        return fixed() ? Math.addExact(cash, credit) : 0;
    }
}
