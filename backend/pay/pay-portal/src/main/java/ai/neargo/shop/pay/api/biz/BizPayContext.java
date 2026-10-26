package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import org.springframework.stereotype.Component;

/**
 * 商家端「当前门店 → 支付的调用方」：门店只取 {@code BizContext} 的当前门店（请求头 {@code X-Store-No}，已按授权校验过），
 * 不接受入参里的门店号（F02）；调用方是商家操作员（{@link InternalCallers#merchantOperator}），门店范围只有这一家。
 */
@Component
public class BizPayContext {

    private final PayMerchantBindingService stores;
    private final InternalCallers callers;

    public BizPayContext(PayMerchantBindingService stores, InternalCallers callers) {
        this.stores = stores;
        this.callers = callers;
    }

    public Long storeId() {
        String storeNo = BizContext.requireStoreNo();
        return stores.storeByNo(storeNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)).storeId();
    }

    /** 收银台接入方名下、本店、操作员 = 当前商家账号 */
    public PayCaller caller(Long storeId) {
        return callers.merchantOperator(InternalCallers.CHECKOUT, storeId, SecurityUtils.currentUserNo());
    }
}
