package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commercebridge.FoundationPaymentAccess;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家在 App 提交「申请支付服务」资料（TDD-运营端申请支付服务 §7）：在独立支付服务 near-pay 建平台商户、
 * 来源链接与门店关联，资料修订进入待审。之后运营在支付运营端点「确认审核」一次通过（单人审批模式）。
 * 与运营代办同一份组装逻辑，只是操作人身份为 MERCHANT。
 */
@Profile("api")
@RestController
public class BizPayServiceController {

    private final StoreSnapshotReader stores;
    private final StoreSyncProperties props;
    private final ObjectProvider<PaySourceLinkApi> sourceLinks;
    private final MerchantEntityService entities;
    private final FoundationPaymentAccess access;

    public BizPayServiceController(StoreSnapshotReader stores, StoreSyncProperties props,
                                   ObjectProvider<PaySourceLinkApi> sourceLinks, MerchantEntityService entities,
                                   FoundationPaymentAccess access) {
        this.stores = stores;
        this.props = props;
        this.sourceLinks = sourceLinks;
        this.entities = entities;
        this.access = access;
    }

    /** 申请审核状态（App 收款 Tab 的开通引导据此显示「审核中 / 需补件 / 未通过」） */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @org.springframework.web.bind.annotation.GetMapping("/biz/merchant/pay-service")
    public PaySourceLinkApi.Status status(HttpServletRequest request) {
        PaySourceLinkApi api = sourceLinks.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        String entityNo = access.require(request, entities.requireOwned(SecurityUtils.currentUserNo(), null), null).entityNo();
        try {
            return api.status(props.sourceScope(), entityNo);
        } catch (ServiceCallException e) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/merchant/pay-service/apply")
    public PaySourceLinkApi.Result apply(@RequestBody PayServiceApplyRequests.Form form, HttpServletRequest request) {
        PaySourceLinkApi api = sourceLinks.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        String user = SecurityUtils.currentUserNo();
        String entityNo = access.require(request, entities.requireOwned(user, null), null).entityNo();
        var entity = stores.entity(entityNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        var body = PayServiceApplyRequests.build(entity, stores.storesOfEntity(entityNo), form, "MERCHANT", user, props);
        try {
            return api.apply(body);
        } catch (ServiceCallException e) {
            throw BizException.of(e.statusCode() == 400 ? ErrorCode.BAD_REQUEST : ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }
}
