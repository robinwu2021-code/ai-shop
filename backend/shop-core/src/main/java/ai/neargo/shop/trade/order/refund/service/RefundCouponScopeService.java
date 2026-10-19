package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.spi.marketing.CouponPort;

/** Saves the original allocation returned by marketing, without recomputing coupon rules. */
public interface RefundCouponScopeService {
    void freeze(String orderNo,String couponNo,CouponPort.Allocation allocation);
}
