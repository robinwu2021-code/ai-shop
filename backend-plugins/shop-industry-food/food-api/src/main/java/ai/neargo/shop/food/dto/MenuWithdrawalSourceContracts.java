package ai.neargo.shop.food.dto;

import java.time.Instant;

/**
 * Only a real DISABLED working head and its original audit action can prove withdrawal of a prior
 * publication.
 */
public final class MenuWithdrawalSourceContracts {
    private MenuWithdrawalSourceContracts() {}

    public record Query(String entityNo, String storeNo, String menuNo, String previousRevision) {}

    public record Data(
            Query query,
            long menuVersion,
            String previousContentHash,
            String actionNo,
            String actionHash,
            Instant withdrawnAt) {}

    public record Disposition(
            Query query,
            String headStatus,
            long headVersion,
            Proof withdrawal,
            Instant verifiedAt,
            Instant validUntil,
            String receiptNo) {}

    public record Proof(Data data, Instant verifiedAt, Instant validUntil, String receiptNo) {}
}
