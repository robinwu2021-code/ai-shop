package ai.neargo.shop.pay.points.model;

import java.util.Map;

/** Read models cannot mutate balances or expose a financial write port. */
public record AccountSnapshot(
        String typeCode,
        HolderType holderType,
        String holderNo,
        Map<Bucket, Long> buckets,
        boolean frozen,
        Long expireAt,
        long expiryRevision,
        long lastActiveAt) {
    public AccountSnapshot {
        buckets = Map.copyOf(buckets);
    }

    public AccountSnapshot(
            String typeCode,
            HolderType holderType,
            String holderNo,
            Map<Bucket, Long> buckets,
            boolean frozen,
            Long expireAt,
            long expiryRevision) {
        this(typeCode, holderType, holderNo, buckets, frozen, expireAt, expiryRevision, 0);
    }

    /** Missing buckets represent a new empty account, not a newly issued right. */
    public long points(Bucket bucket) {
        return buckets.getOrDefault(bucket, 0L);
    }
}
