package ai.neargo.shop.pay.api.guard.terminal;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.CachedBodyRequest;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.core.PortalRequests;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * POS 终端入口 {@code /terminal/pay/v1/**}（pay-guard TDD §2.2 C；开发计划 M6.2）：{@code X-Terminal-Sn} · {@code X-Timestamp} ·
 * {@code X-Nonce} · {@code X-Signature} · {@code X-Operator}（收银员登录后）。认不出 → 401、不进 controller、写请求记录。
 *
 * <p><b>激活接口不验签</b>（终端还没有密钥）：放行到 controller，由 {@code TerminalService#activate} 验一次性激活码。
 * 响应按 P2 用设备密钥签名 —— 终端据此判断「收款成功」不是被中间人伪造的。
 */
@ConditionalOnGuardEntry(PortalFace.TERMINAL)
@Component
public class TerminalGuard implements EntryGuard {

    public static final String ACTIVATION_PATH = PortalFace.TERMINAL.prefix() + "/activation";

    private static final Logger log = LoggerFactory.getLogger(TerminalGuard.class);

    private final TerminalAuthService auth;

    public TerminalGuard(TerminalAuthService auth) {
        this.auth = auth;
    }

    @Override
    public PortalFace entry() {
        return PortalFace.TERMINAL;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (ACTIVATION_PATH.equals(path) && "POST".equals(request.getMethod())) {
            return GuardResult.ok(new PortalPrincipal(PortalFace.TERMINAL, "ACTIVATION", "-", null, Set.of()));
        }
        byte[] body = request instanceof CachedBodyRequest c ? c.body() : new byte[0];
        var r = auth.authenticate(new TerminalAuthService.SignedRequest(request.getHeader("X-Terminal-Sn"),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), request.getHeader("X-Signature"),
                request.getMethod(), path, SignatureCanonicalizer.parseQuery(request.getQueryString()),
                SignatureCanonicalizer.sha256Hex(body)));
        if (!r.ok()) {
            log.info("[terminal] 鉴权失败 sn={} path={}：{}", request.getHeader("X-Terminal-Sn"), path, r.failure());
            if (r.expired()) {
                return new GuardResult(null, 401, ErrorCode.PAY_OPEN_TIMESTAMP_EXPIRED, "REJECTED_AUTH", r.failure());
            }
            if (r.replayed()) {
                return new GuardResult(null, 401, ErrorCode.PAY_OPEN_REPLAYED, "REJECTED_AUTH", r.failure());
            }
            return GuardResult.unauthorized(r.failure());
        }
        request.setAttribute(PortalRequests.SIGN_KEY_VERSION, r.keyVersion());
        return GuardResult.ok(new PortalPrincipal(PortalFace.TERMINAL, "TERMINAL", r.terminal().getTerminalSn(),
                r.terminal().getStoreId(), Set.of()));
    }

    @Override
    public boolean signsResponses() {
        return true;
    }

    @Override
    public void signResponse(HttpServletRequest request, HttpServletResponse response, byte[] body, PortalPrincipal who) {
        if (who == null || !"TERMINAL".equals(who.subjectType())) {
            return;
        }
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String toSign = ts + "\n" + nonce + "\n" + SignatureCanonicalizer.sha256Hex(body == null ? new byte[0] : body);
        auth.sign(who.subjectId(), toSign.getBytes(StandardCharsets.UTF_8)).ifPresent(sig -> {
            response.setHeader("X-Timestamp", ts);
            response.setHeader("X-Nonce", nonce);
            response.setHeader("X-Signature", sig);
        });
    }
}
