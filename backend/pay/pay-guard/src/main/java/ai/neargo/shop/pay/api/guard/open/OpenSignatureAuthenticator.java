package ai.neargo.shop.pay.api.guard.open;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.CachedBodyRequest;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ai.neargo.shop.pay.api.guard.core.PortalAuthFilter;
import ai.neargo.shop.pay.api.guard.core.PortalAuthenticator;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;

/**
 * 开放 API 鉴权（安全规范 §2.3）：读 {@code X-App-Id / X-Timestamp / X-Nonce / X-Signature} 与缓存的原始请求体，交给
 * {@link AppAuthService}。失败原因只进日志与请求记录，回给调用方的只有统一的 401。
 */
@Component
public class OpenSignatureAuthenticator implements PortalAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(OpenSignatureAuthenticator.class);

    private final AppAuthService auth;

    public OpenSignatureAuthenticator(AppAuthService auth) {
        this.auth = auth;
    }

    @Override
    public PortalFace face() {
        return PortalFace.OPEN;
    }

    @Override
    public Optional<PortalPrincipal> authenticate(HttpServletRequest request) {
        byte[] body = request instanceof CachedBodyRequest c ? c.body() : new byte[0];
        Map<String, List<String>> query = SignatureCanonicalizer.parseQuery(request.getQueryString());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var result = auth.authenticate(new AppAuthService.SignedRequest(request.getHeader("X-App-Id"),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), request.getHeader("X-Signature"),
                request.getMethod(), path, query, SignatureCanonicalizer.sha256Hex(body), ClientMeta.clientIp(request)));
        if (!result.ok()) {
            log.info("[open-api] 鉴权失败 app={} path={}：{}", request.getHeader("X-App-Id"), path, result.failure());
            request.setAttribute(PortalAuthFilter.FAILURE, result.failure());
            return Optional.empty();
        }
        return Optional.of(new PortalPrincipal(PortalFace.OPEN, "APP", result.app().getAppNo(), null, Set.of()));
    }
}
