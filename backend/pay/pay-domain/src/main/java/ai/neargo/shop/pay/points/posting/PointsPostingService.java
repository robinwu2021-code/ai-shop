package ai.neargo.shop.pay.points.posting;

import ai.neargo.shop.pay.points.model.Voucher;

/** Sole points financial entry; implementation joins the existing pay transaction. */
public interface PointsPostingService {
    /** Posts a conserved voucher or returns the original immutable result of an exact replay. */
    PostingResult post(Voucher voucher);
}
