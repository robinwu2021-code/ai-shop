package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PayServiceApplicationService;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
@Validated
public class OpsPayServiceController {

    private final StoreSnapshotReader stores;
    private final StoreSyncProperties props;
    private final ObjectProvider<PaySourceLinkApi> sourceLinks;
    private final PayServiceApplicationService applications;
    private final String opsBase;

    public OpsPayServiceController(StoreSnapshotReader stores, StoreSyncProperties props,
                                   ObjectProvider<PaySourceLinkApi> sourceLinks, PayServiceApplicationService applications,
                                   @Value("${shop.pay.ops-public-base-url:}") String opsBase) {
        this.stores = stores;
        this.props = props;
        this.sourceLinks = sourceLinks;
        this.applications = applications;
        this.opsBase = trustedOpsBase(opsBase);
    }

    /** 来源/资料状态不代表账户、产品、凭据和门店路由已就绪。 */
    public record Status(String status, String merchantRef, String linkStatus, String profileStatus,
                         String reviewReason, String merchantOpsUrl, String onboardingOpsUrl) {}

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_PAY_APPLY + "')")
    @GetMapping("/ops/merchant/{entityNo}/pay-service")
    public Status status(@PathVariable String entityNo) {
        var entity = stores.entity(entityNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        var api = sourceLinks.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        PaySourceLinkApi.Status status;
        try {
            status = api.status(props.sourceScope(), entity.entityNo());
        } catch (ServiceCallException failure) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        if (status == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        String merchantUrl = opsBase.isEmpty() || status.merchantRef() == null ? null
                : opsBase + "/merchants?keyword=" + URLEncoder.encode(status.merchantRef(), StandardCharsets.UTF_8);
        return new Status(status.status(), status.merchantRef(), status.linkStatus(), status.profileStatus(),
                status.reviewReason(), merchantUrl, opsBase.isEmpty() ? null : opsBase + "/merchants/onboarding-review");
    }

    private static String trustedOpsBase(String configured) {
        if (configured == null || configured.isBlank()) return "";
        URI uri = URI.create(configured.trim());
        boolean local = "http".equals(uri.getScheme()) && uri.getHost() != null
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("shop.pay.ops-public-base-url requires a trusted HTTPS base URL");
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_PAY_APPLY + "')")
    @PostMapping("/ops/merchant/{entityNo}/pay-service/apply")
    public PaySourceLinkApi.Result apply(@PathVariable String entityNo, @Valid @RequestBody PayServiceApplyRequests.ApplyReq form) {
        PaySourceLinkApi api = sourceLinks.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        return applications.apply(entityNo,form,"OPERATOR",SecurityUtils.currentUserNoOrNull());
    }
}
