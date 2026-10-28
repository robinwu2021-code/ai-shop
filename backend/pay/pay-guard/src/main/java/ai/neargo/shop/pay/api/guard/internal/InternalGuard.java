package ai.neargo.shop.pay.api.guard.internal;

import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
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
 * <b>默认关</b>（用户 2026-10-27 定）。关着时内部入口唯一的边界是网络隔离（nginx {@code deny /internal/}、内网端口）。
 */
@ConditionalOnGuardEntry(PortalFace.INTERNAL)
@Component
public class InternalGuard implements EntryGuard {

    /** 端点读这个属性拿调用方码（只放行它自己的业务类型）；属性只能由本守卫写 */
    public static final String CALLER_ATTRIBUTE = InternalGuard.class.getName() + ".caller";
    /** 解出来的来源（{@link CallSource}）；记录器读它 */
    public static final String SOURCE_ATTRIBUTE = InternalGuard.class.getName() + ".source";

    private final InternalCallerRegistry callers;
    private final boolean verifyCredential;

    public InternalGuard(InternalCallerRegistry callers,
                         @Value("${shop.pay.guard.internal.verify-credential:false}") boolean verifyCredential) {
        this.callers = callers;
        this.verifyCredential = verifyCredential;
    }

    @Override
    public PortalFace entry() {
        return PortalFace.INTERNAL;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        String header = request.getHeader(InternalHeaders.CALLER);
        String caller = header == null || header.isBlank() ? CallSources.UNKNOWN : header.trim();
        if (verifyCredential && callers.resolve(header, request.getHeader(InternalHeaders.CREDENTIAL)).isEmpty()) {
            return GuardResult.unauthorized("UNKNOWN_INTERNAL_CALLER " + caller);
        }
        CallSource source = CallSourceCodec.decode(request.getHeader(InternalHeaders.CALL_SOURCE))
                .orElseGet(InternalGuard::unknown)
                .withModuleIfAbsent(CallSources.UNKNOWN.equals(caller) ? null : caller);
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        request.setAttribute(SOURCE_ATTRIBUTE, source);
        return GuardResult.ok(new PortalPrincipal(PortalFace.INTERNAL, "INTERNAL_CALLER", caller, null, Set.of()));
    }

    /** 没带或解不开来源头：只知道「有人调了」 */
    private static CallSource unknown() {
        return new CallSource(CallSources.UNKNOWN, null, null, null, null, null, CallSources.UNKNOWN, null, null, null, null);
    }
}
