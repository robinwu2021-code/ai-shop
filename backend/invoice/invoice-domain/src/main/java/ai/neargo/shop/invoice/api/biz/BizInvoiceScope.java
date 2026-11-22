package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;

/** B 端发票请求的作用域：租户与操作人取自登录态，主体取 {@code BizContext}，门店取 {@code X-Store-No}。 */
final class BizInvoiceScope {

    private BizInvoiceScope() {
    }

    static Scope current() {
        var user = SecurityUtils.requireUser();
        return new Scope(user.tenantNo(), BizContext.requireMerchantNo(), BizContext.requireStoreNo(), user.userNo());
    }
}
