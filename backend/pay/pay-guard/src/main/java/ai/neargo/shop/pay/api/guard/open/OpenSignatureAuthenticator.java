package ai.neargo.shop.pay.api.guard.open;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.CachedBodyRequest;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.api.guard.core.PortalActions;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.core.PortalRequests;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;

/**
 * 开放 API 鉴权（安全规范 §2.3）：读 {@code X-App-Id / X-Timestamp / X-Nonce / X-Signature} 与缓存的原始请求体，交给
 * {@link AppAuthService}。失败原因只进日志与请求记录，回给调用方的只有统一的 401。
 */
@ConditionalOnGuardEntry(PortalFace.OPEN)
@Component
public class OpenSignatureAuthenticator implements EntryGuard {

    private static final Logger log = LoggerFactory.getLogger(OpenSignatureAuthenticator.class);

    private final AppAuthService auth;

    public OpenSignatureAuthenticator(AppAuthService auth) {
        this.auth = auth;
    }

    @Override
    public PortalFace entry() {
        return PortalFace.OPEN;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        byte[] body = request instanceof CachedBodyRequest c ? c.body() : new byte[0];
        Map<String, List<String>> query = SignatureCanonicalizer.parseQuery(request.getQueryString());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var result = auth.authenticate(new AppAuthService.SignedRequest(request.getHeader("X-App-Id"),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), request.getHeader("X-Signature"),
                request.getHeader("X-Key-Serial"), request.getMethod(), path, query, SignatureCanonicalizer.sha256Hex(body), ClientMeta.clientIp(request)));
        if (!result.ok()) {
            log.info("[open-api] 鉴权失败 app={} path={}：{}", request.getHeader("X-App-Id"), path, result.failure());
            return rejected(result);
        }
        // 命中的密钥版本记进请求记录（⑫），统计「还有谁在用旧版本」靠它（M3.2）
        request.setAttribute(PortalRequests.SIGN_KEY_VERSION, result.keyVersion());
        // ⑧ scope：按动作要 scope，缺了就在这里 403，不进 controller、不解析请求体（M3.3）；认不出的动作一律拒
        String scope = AppScopes.requiredFor(PortalActions.of(PortalFace.OPEN, request.getMethod(), path));
        if (scope == null || !AppScopes.granted(result.app(), scope)) {
            return new GuardResult(null, 403, ErrorCode.FORBIDDEN, "REJECTED_FORBIDDEN",
                    "SCOPE_MISSING " + (scope == null ? "(unknown action)" : scope));
        }
        return GuardResult.ok(new PortalPrincipal(PortalFace.OPEN, "APP", result.app().getAppNo(), null, Set.of()));
    }

    /**
     * 失败种类 → 状态与错误码（API 清单 §1.3；开发计划 M3.7）：时钟偏了、nonce 重放、IP 不在白名单是接入方自己能改的，
     * 各给自己的码；其余（签名错、AppId 不存在、环境不符、缺头）统一 401 —— 原因只进记录。
     */
    static GuardResult rejected(AppAuthService.Result result) {
        return switch (result.kind()) {
            case TIMESTAMP -> new GuardResult(null, 401, ErrorCode.PAY_OPEN_TIMESTAMP_EXPIRED, "REJECTED_AUTH",
                    result.failure());
            case REPLAYED -> new GuardResult(null, 401, ErrorCode.PAY_OPEN_REPLAYED, "REJECTED_AUTH", result.failure());
            case IP -> new GuardResult(null, 403, ErrorCode.FORBIDDEN, "REJECTED_FORBIDDEN", result.failure());
            default -> GuardResult.unauthorized(result.failure());
        };
    }
}
