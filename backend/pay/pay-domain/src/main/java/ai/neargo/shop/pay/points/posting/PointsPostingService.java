package ai.neargo.shop.pay.points.posting;

import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.model.EvidenceRef;

/** Sole points financial entry; implementation joins the existing pay transaction. */
public interface PointsPostingService {
    /** Posts a conserved voucher or returns the original immutable result of an exact replay. */
    PostingResult post(Voucher voucher);
    /** Trusted result confirmation registers a verified receipt before consuming it in the same pay transaction. */
    void recordEvidence(EvidenceRef evidence);
}
