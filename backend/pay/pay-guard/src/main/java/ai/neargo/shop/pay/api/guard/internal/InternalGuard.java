package ai.neargo.shop.pay.api.guard.internal;

import ai.neargo.svc.context.SignedContextCodec;
import ai.neargo.svc.guard.SignedContextVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.api.guard.core.IpAllowList;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内部入口 {@code /internal/pay/v1}（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.3 A、§2.7.4）：
 * <b>只识别、不拒绝</b>。读 {@code X-Internal-Caller}（调用方码）与 {@code X-Call-Source}（来源），放进请求属性，
 * 管线 ⑫ 把来源写进请求记录；缺失或损坏照样放行、记 {@code UNKNOWN}。
 *
 * <p>原 {@code InternalCallerFilter} 的「调用方码 + 凭据」校验保留为开关 {@code shop.pay.guard.internal.verify-credential}，
 * <b>默认关</b>（用户 2026-10-27 定）。关着时内部入口的边界是网络隔离（nginx {@code deny /internal/}、内网端口）
 * 加来源 IP 白名单 {@code shop.pay.guard.internal.allowed-ips}（开发计划 M2.8，用户 2026-10-28 加）：只认 TCP 对端地址，
 * 不读任何代理头；默认只放本机回环（今天 {@code pay-svc} 与 {@code shop-app} 同机），拆机部署时配成内网网段，配空 = 不限。
 */
@ConditionalOnGuardEntry(PortalFace.INTERNAL)
@Component
public class InternalGuard implements EntryGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InternalGuard.class);

    /** 端点读这个属性拿调用方码（只放行它自己的业务类型）；属性只能由本守卫写 */
    public static final String CALLER_ATTRIBUTE = InternalGuard.class.getName() + ".caller";
    /** 解出来的来源（{@link CallSource}）；记录器读它 */
    public static final String SOURCE_ATTRIBUTE = InternalGuard.class.getName() + ".source";

    /** 来源 IP 白名单的默认值：本机回环（IPv4 与 IPv6） */
    public static final String DEFAULT_ALLOWED_IPS = "127.0.0.1/32,::1/128";

    private final InternalCallerRegistry callers;
    private final boolean verifyCredential;
    private final String allowedIps;

    /** 网关的签名上下文：只比对、记日志，从不因此拒绝（TDD-网关层与统一权限 §2.7；打开拒绝需另行确认）。 */
    private SignedContextVerifier signedContext;

    @Autowired
    void signedContext(@Value("${shop.gateway.signing.key:}") String key,
                       @Value("${shop.pay.guard.internal.signed-context:observe}") String mode) {
        this.signedContext = key == null || key.isBlank() || !"observe".equalsIgnoreCase(mode)
                ? null : new SignedContextVerifier(key.trim());
    }

    public InternalGuard(InternalCallerRegistry callers,
                         @Value("${shop.pay.guard.internal.verify-credential:false}") boolean verifyCredential,
                         @Value("${shop.pay.guard.internal.allowed-ips:" + DEFAULT_ALLOWED_IPS + "}") String allowedIps) {
        this.callers = callers;
        this.verifyCredential = verifyCredential;
        this.allowedIps = allowedIps;
    }

    @Override
    public PortalFace entry() {
        return PortalFace.INTERNAL;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        // 白名单只认 TCP 对端：内部调用不经 nginx，任何代理头都是调用方自己写的
        String peer = request.getRemoteAddr();
        if (!IpAllowList.allows(allowedIps, peer)) {
            return new GuardResult(null, 403, ErrorCode.FORBIDDEN, "REJECTED_FORBIDDEN", "IP_NOT_ALLOWED " + peer);
        }
        String header = request.getHeader(InternalHeaders.CALLER);
        String caller = header == null || header.isBlank() ? CallSources.UNKNOWN : header.trim();
        if (verifyCredential && callers.resolve(header, request.getHeader(InternalHeaders.CREDENTIAL)).isEmpty()) {
            return GuardResult.unauthorized("UNKNOWN_INTERNAL_CALLER " + caller);
        }
        CallSource source = CallSourceCodec.decode(request.getHeader(InternalHeaders.CALL_SOURCE))
                .orElseGet(InternalGuard::unknown)
                .withModuleIfAbsent(CallSources.UNKNOWN.equals(caller) ? null : caller);
        observeSignedContext(request, caller);
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        request.setAttribute(SOURCE_ATTRIBUTE, source);
        return GuardResult.ok(new PortalPrincipal(PortalFace.INTERNAL, "INTERNAL_CALLER", caller, null, Set.of()));
    }

    /**
     * 比对签名上下文。这一处拿不到请求体，所以只验签名、时间窗、方法与地址。
     * 没带的记 debug（别的调用方还没开始签）；带了但对不上的记 warn —— 那才是打开拒绝之前要逐条看清的。
     */
    private void observeSignedContext(HttpServletRequest request, String caller) {
        if (signedContext == null) {
            return;
        }
        String target = request.getRequestURI().substring(request.getContextPath().length())
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        SignedContextVerifier.Result result = signedContext.verify(request.getHeader(SignedContextCodec.HEADER_CONTEXT),
                request.getHeader(SignedContextCodec.HEADER_SIGNATURE), request.getMethod(), target, null,
                System.currentTimeMillis());
        if (result.outcome() == SignedContextVerifier.Outcome.MISSING) {
            log.debug("内部调用没带签名上下文 caller={} {} {}", caller, request.getMethod(), target);
        } else if (!result.ok()) {
            log.warn("内部调用的签名上下文对不上（只比对，已放行）caller={} {} {} · {}", caller, request.getMethod(),
                    target, result.outcome());
        }
    }

    /** 没带或解不开来源头：只知道「有人调了」 */
    private static CallSource unknown() {
        return new CallSource(CallSources.UNKNOWN, null, null, null, null, null, CallSources.UNKNOWN, null, null, null, null);
    }
}
