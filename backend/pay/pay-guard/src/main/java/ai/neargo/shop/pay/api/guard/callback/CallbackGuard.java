package ai.neargo.shop.pay.api.guard.callback;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.CachedBodyRequest;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.spi.pay.PayCallbackPort;
import ai.neargo.shop.spi.pay.PayCallbackPort.Envelope;
import ai.neargo.shop.spi.pay.PayCallbackPort.Kind;
import ai.neargo.shop.spi.pay.PayCallbackPort.RouteStyle;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verified;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 渠道回调入口 {@code /pay/callback/**}（开发计划 M4.1；安全规范 §2.4 / G11）：解析路径成信封 → 交支付域的验签前段
 * （先落原文 → 按渠道与收款商户验签 → 核对路径里的收款商户）。不过就按渠道格式回 {@code FAIL}（HTTP 200，渠道据此重推），
 * <b>不进 controller</b>；过了把结果放进请求属性，controller 只做回查 → 比对 → 幂等入账。
 *
 * <p>路径：新地址 {@code /{channel}/{pay|refund}/{收款商户号}}（POST / GET）；旧地址别名 {@code /{channel}}（支付）、
 * {@code /{channel}/refund}（退款）。开发期 stub {@code /pay/callback/stub} 不归本守卫管（它自己验共享密钥）。
 * 回调有自己的记录（{@code pay_callback_log}），不写请求记录。
 */
@ConditionalOnGuardEntry(PortalFace.CALLBACK)
@Component
public class CallbackGuard implements EntryGuard {

    /** controller 读这个属性拿验签结果（{@link Verified}）；只有本守卫写 */
    public static final String VERIFIED_ATTRIBUTE = CallbackGuard.class.getName() + ".verified";

    private static final String STUB = "/stub";

    private final PayCallbackPort callbacks;

    public CallbackGuard(PayCallbackPort callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public PortalFace entry() {
        return PortalFace.CALLBACK;
    }

    @Override
    public boolean recordsRequests() {
        return false;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length())
                .substring(PortalFace.CALLBACK.prefix().length());
        if (path.equals(STUB)) {
            return GuardResult.ok(new PortalPrincipal(PortalFace.CALLBACK, "CHANNEL", "STUB", null, Set.of()));
        }
        Envelope env = envelope(request, path.startsWith("/") ? path.substring(1).split("/") : new String[0]);
        if (env == null) {
            // 不支持的通知类型 / 地址形态：当没这个端点，不落记录
            return GuardResult.raw(200, "FAIL", "REJECTED_PARAM", "UNSUPPORTED_ROUTE " + path);
        }
        Verified v = callbacks.verify(env);
        if (v.rejection() != null) {
            return GuardResult.raw(200, v.rejection().ack(), "REJECTED_AUTH", v.rejection().reason());
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, v);
        return GuardResult.ok(new PortalPrincipal(PortalFace.CALLBACK, "CHANNEL", env.payChannel(), null, Set.of()));
    }

    private static Envelope envelope(HttpServletRequest req, String[] seg) {
        Kind kind;
        String ref;
        RouteStyle style;
        if (seg.length == 3) {
            kind = switch (seg[1]) {
                case "pay" -> Kind.PAY;
                case "refund" -> Kind.REFUND;
                default -> null;
            };
            ref = seg[2];
            style = RouteStyle.PATH;
        } else if (seg.length == 2 && "refund".equals(seg[1]) && "POST".equals(req.getMethod())) {
            kind = Kind.REFUND;
            ref = "-";
            style = RouteStyle.LEGACY;
        } else if (seg.length == 1 && !seg[0].isEmpty() && "POST".equals(req.getMethod())) {
            kind = Kind.PAY;
            ref = "-";
            style = RouteStyle.LEGACY;
        } else {
            return null;
        }
        if (kind == null) {
            return null;
        }
        String body = req instanceof CachedBodyRequest c && c.body().length > 0
                ? new String(c.body(), StandardCharsets.UTF_8) : null;
        return new Envelope(seg[0], kind, ref, style, req.getMethod(), ClientMeta.clientIp(req), headers(req), body);
    }

    /** 原样的头（名字保持大小写；验签实现自己不区分大小写地取） */
    private static Map<String, String> headers(HttpServletRequest req) {
        Map<String, String> h = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            h.put(name, req.getHeader(name));
        }
        return h;
    }
}
