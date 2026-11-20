package ai.neargo.shop.paybridge;

import ai.neargo.shop.trade.entity.TrdShippingUpload;

import java.util.List;

/** Durable payment queue revision; conditional writes never acknowledge a different package set. */
public interface ShippingUploadQueue {
    void lockPayment(String orderNo);
    TrdShippingUpload byOrder(String orderNo);
    TrdShippingUpload byOrderLocked(String orderNo);
    void insert(TrdShippingUpload row);
    boolean refresh(TrdShippingUpload row, String snapshot);
    boolean finish(TrdShippingUpload row, WxShippingUploadService.Outcome outcome,
                   Integer errorCode, String errorMessage);
    void blocked(TrdShippingUpload row, String reason);
    List<TrdShippingUpload> pending(int limit);
    String statusOf(Long id);
}
