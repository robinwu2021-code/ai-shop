package ai.neargo.shop.pay.points.model;

/** Stable keys ensure callers cannot choose lock order by arranging a request list. */
public record AccountKey(String typeCode, HolderType holderType, String holderNo, Bucket bucket)
        implements Comparable<AccountKey> {
    @Override
    public int compareTo(AccountKey other) {
        int result = typeCode.compareTo(other.typeCode);
        if (result == 0) { result = holderType.compareTo(other.holderType); }
        if (result == 0) { result = holderNo.compareTo(other.holderNo); }
        if (result == 0) { result = bucket.compareTo(other.bucket); }
        return result;
    }
}
