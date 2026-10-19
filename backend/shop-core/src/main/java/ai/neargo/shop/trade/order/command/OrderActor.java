package ai.neargo.shop.trade.order.command;

import ai.neargo.shop.trade.entity.OrdSubOrder;

/** Explicit authenticated merchant/store or buyer identity keeps command ownership independent of ambient scope. */
public record OrderActor(String merchantNo, String storeNo, String operatorNo, String buyerNo) {
    /** Merchant callers retain their existing identity without gaining buyer receipt authority. */
    public OrderActor(String merchantNo, String storeNo, String operatorNo) {
        this(merchantNo, storeNo, operatorNo, null);
    }
    /** Preserve trusted internal callers while real B routes supply their authenticated person. */
    public OrderActor(String merchantNo, String storeNo) {
        this(merchantNo, storeNo, merchantNo, null);
    }

    /** Bind consumer commands to an authenticated buyer, distinct from merchant/store authorization. */
    public static OrderActor buyer(String userNo) {
        return new OrderActor(null, null, userNo, userNo);
    }

    /** Preserve the legacy optional store scope for authorized internal callers. */
    public boolean owns(OrdSubOrder order) {
        return merchantNo != null && !merchantNo.isBlank() && merchantNo.equals(order.getEntityNo())
                && (storeNo == null || storeNo.isBlank() || storeNo.equals(order.getStoreNo()));
    }
}
