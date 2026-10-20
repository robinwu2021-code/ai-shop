package ai.neargo.shop.trade.entitlement;

/** The source owner persists terminal decisions in its own transaction. */
public record SourceDecisionRecord(String tenantNo, String purpose, String sourceKey, String settledSourceKey,
                                  long sourceRevision, Status status, String decisionNo) {
    public enum Status { COMMITTED, CANCELLED }

    public SourceDecisionRecord {
        required(tenantNo); required(purpose); required(sourceKey); required(decisionNo);
        if (sourceRevision < 0 || status == null) throw new IllegalArgumentException("invalid decision");
        if (status == Status.COMMITTED) required(settledSourceKey);
        if (status == Status.CANCELLED && settledSourceKey != null) {
            throw new IllegalArgumentException("cancelled decision cannot settle source");
        }
    }

    public static SourceDecisionRecord cancelled(String tenant, String purpose, String sourceKey,
                                                 long revision, String decisionNo) {
        return new SourceDecisionRecord(tenant, purpose, sourceKey, null, revision, Status.CANCELLED, decisionNo);
    }

    public static SourceDecisionRecord committed(String tenant, String purpose, String sourceKey,
                                                 String settledKey, long revision, String decisionNo) {
        return new SourceDecisionRecord(tenant, purpose, sourceKey, settledKey, revision,
                Status.COMMITTED, decisionNo);
    }

    private static void required(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("required value");
        }
    }
}
