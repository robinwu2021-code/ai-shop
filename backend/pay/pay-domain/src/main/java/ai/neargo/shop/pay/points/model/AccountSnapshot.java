package ai.neargo.shop.pay.points.model;

import java.util.Map;

/** Read models cannot mutate balances or expose a financial write port. */
public record AccountSnapshot(String typeCode, HolderType holderType, String holderNo, Map<Bucket, Long> buckets,
                              boolean frozen, Long expireAt, long expiryRevision) {
    public AccountSnapshot { buckets = Map.copyOf(buckets); }
    /** Missing buckets represent a new empty account, not a newly issued right. */
    public long points(Bucket bucket) { return buckets.getOrDefault(bucket, 0L); }
}
