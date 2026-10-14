package ai.neargo.shop.trade.order.command;

import ai.neargo.shop.trade.entity.OrdSubOrder;

/** Authenticated merchant/store scope supplied by the existing B authorization boundary. */
public record OrderActor(String merchantNo, String storeNo, String operatorNo) {
    /** Preserve trusted internal callers while real B routes supply their authenticated person. */
    public OrderActor(String merchantNo, String storeNo) {
        this(merchantNo, storeNo, merchantNo);
    }

    /** Preserve the legacy optional store scope for authorized internal callers. */
    public boolean owns(OrdSubOrder order) {
        return merchantNo != null && !merchantNo.isBlank() && merchantNo.equals(order.getEntityNo())
                && (storeNo == null || storeNo.isBlank() || storeNo.equals(order.getStoreNo()));
    }
}
