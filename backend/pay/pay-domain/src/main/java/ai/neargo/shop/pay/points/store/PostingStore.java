package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.posting.PostingResult;

/** Only PointsPostingService may consume this financial write port. */
public interface PostingStore {
    /** Claims the unique business command; returns its prior result for an exact replay. */
    PostingResult claim(Voucher voucher, String hash, String voucherNo);
    /** Locks in type/object order and applies all guarded changes inside the caller's pay transaction. */
    void applyConditions(Voucher voucher, String voucherNo);
    /** Adds immutable verified proof; an existing identity must have exactly the same currency and money amount. */
    void recordEvidence(EvidenceRef evidence);
}
