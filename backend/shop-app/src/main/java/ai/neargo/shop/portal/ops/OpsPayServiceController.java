package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营为主体申请支付服务（TDD-运营端申请支付服务）：在独立支付服务 near-pay 里建平台商户、来源链接与门店关联。
 * 之后的核验来源链接、登记渠道账户、录入凭证、绑定门店，在 near-pay 运营端完成。
 *
 * <p>内嵌部署（没有独立支付服务）时没有 {@link PaySourceLinkApi}，明确报依赖不可用，不假装成功。
 */
@Profile("ops")
@RestController
public class OpsPayServiceController {

    private final StoreSnapshotReader stores;
    private final StoreSyncProperties props;
    private final ObjectProvider<PaySourceLinkApi> sourceLinks;

    public OpsPayServiceController(StoreSnapshotReader stores, StoreSyncProperties props,
                                   ObjectProvider<PaySourceLinkApi> sourceLinks) {
        this.stores = stores;
        this.props = props;
        this.sourceLinks = sourceLinks;
    }

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_PAY_APPLY + "')")
    @PostMapping("/ops/merchant/{entityNo}/pay-service/apply")
    public PaySourceLinkApi.Result apply(@PathVariable String entityNo, @RequestBody PayServiceApplyRequests.Form form) {
        PaySourceLinkApi api = sourceLinks.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        var entity = stores.entity(entityNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        var body = PayServiceApplyRequests.build(entity, stores.storesOfEntity(entityNo), form,
                SecurityUtils.currentUserNoOrNull(), props);
        try {
            return api.apply(body);
        } catch (ServiceCallException e) {
            // near-pay 内部接口出错只回状态码：400 = 资料不合法，其余按依赖不可用
            throw BizException.of(e.statusCode() == 400 ? ErrorCode.BAD_REQUEST : ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }
}
