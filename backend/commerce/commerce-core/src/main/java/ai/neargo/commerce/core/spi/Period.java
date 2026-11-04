package ai.neargo.commerce.core.spi;

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
        boolean gift,
        Long effectiveEnd,
        long revokedDuration,
        String lifecycle,
        SourceRevocation revocation) {
    public Period(String no,String origin,String sourceType,String planNo,String planVersion,String tier,
            long delivered,long start,Long duration,Map<String,String> ends,long cash,long credit,String currency,
            String policyVersion,boolean gift,Long effectiveEnd,long revokedDuration,String lifecycle) {
        this(no,origin,sourceType,planNo,planVersion,tier,delivered,start,duration,ends,cash,credit,currency,
                policyVersion,gift,effectiveEnd,revokedDuration,lifecycle,null);
    }
    public Period(
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
        this(
                no,
                origin,
                sourceType,
                planNo,
                planVersion,
                tier,
                delivered,
                start,
                duration,
                ends,
                cash,
                credit,
                currency,
                policyVersion,
                gift,
                null,
                0,
                "NORMAL");
    }

    public Period {
        if (policyVersion == null || policyVersion.isBlank())
            throw new IllegalStateException("missing source policy evidence");
        if (lifecycle == null) lifecycle = "NORMAL";
        if (!java.util.Set.of("NORMAL", "CONVERTED", "FROZEN", "REFUNDED", "REVOKED").contains(lifecycle)
                || revokedDuration < 0
                || duration != null && revokedDuration > duration
                || "NORMAL".equals(lifecycle) && (effectiveEnd != null || revokedDuration != 0)
                || !"NORMAL".equals(lifecycle) && (duration == null || effectiveEnd == null)
                || planNo != null
                        && effectiveEnd != null
                        && effectiveEnd
                                != Math.addExact(
                                        start, Math.subtractExact(duration, revokedDuration)))
            throw new IllegalArgumentException("invalid effective source boundary");
        if ("REVOKED".equals(lifecycle)) {
            if (revocation == null || revocation.operationNo() == null || revocation.operationNo().isBlank()
                    || revocation.operatorNo() == null || revocation.operatorNo().isBlank()
                    || revocation.reason() == null || revocation.reason().isBlank()
                    || (!"GRANT".equals(sourceType) && !gift) || cash != 0 || credit != 0 || "FREE".equals(tier)
                    || revocation.cashBasis() != cash || revocation.creditBasis() != credit
                    || revocation.originalDuration() != duration
                    || revocation.usedDuration() != duration - revokedDuration
                    || revocation.effectiveAt() < delivered
                    || effectiveEnd > Math.max(start,revocation.effectiveAt()))
                throw new IllegalArgumentException("unproven zero-value revocation");
        } else if (revocation != null) throw new IllegalArgumentException("unexpected revocation proof");
        ends = Map.copyOf(ends);
    }

    public Long end() {
        return effectiveEnd != null
                ? effectiveEnd
                : duration == null ? null : Math.addExact(start, duration);
    }

    public long usableDuration() {
        return duration == null ? 0 : Math.subtractExact(duration, revokedDuration);
    }

    public boolean fixed() {
        return planNo != null;
    }

    public long basis() {
        return fixed() ? Math.addExact(cash, credit) : 0;
    }
}
