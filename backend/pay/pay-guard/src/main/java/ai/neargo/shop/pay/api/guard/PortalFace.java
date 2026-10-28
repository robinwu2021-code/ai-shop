package ai.neargo.shop.pay.api.guard;

import java.util.Arrays;
import java.util.Optional;

/**
 * 支付接入层的入口：每个入口一个 URL 前缀、一套鉴权（ADR-044 §2-3、ADR-045）。对外入口另有一个 controller 子包。
 *
 * <p>前缀只在这里定义一次。nginx 分流、鉴权过滤器挂载、守卫 {@link PortalRouteRules} 都从这里取，
 * 改一个入口的前缀只改这一行 —— 而不是在五处字符串里各改一遍、漏一处就静默走到别的鉴权链。
 *
 * <p>哪个进程装哪些入口由 {@code shop.pay.guard.entries} 决定（{@link ConditionalOnGuardEntry}）。
 */
public enum PortalFace {

    /**
     * 内部系统（业务服务调支付）：{@code pay-svc} 的 {@code /internal/pay/v1}。端点在 {@code pay-svc}，
     * 不在本包的 controller 子包下 —— {@code PortalRouteRules} 只管 {@link #API_PACKAGE} 里的 controller，管不到它
     */
    INTERNAL("internal", "/internal/pay/v1"),
    /** 支付运营端：运营会话 + {@code pay:*} 权限码 + 关键操作两人复核 */
    OPS("ops", "/pay-ops/v1"),
    /** 第三方开放 API：AppId + 签名、时间窗、nonce */
    OPEN("open", "/open/pay/v1"),
    /** 自研刷卡机：终端号 + 设备密钥签名 + 店长授权码 */
    TERMINAL("terminal", "/terminal/pay/v1"),
    /** 商家端：商家会话 + 商家内权限码 */
    BIZ("biz", "/biz/pay"),
    /** 扫码收银 H5：一次性收银令牌 / 码号，可选 C 端登录 */
    CHECKOUT("checkout", "/checkout/v1"),
    /** 渠道回调：先落原文 → 按渠道验签 → 核对收款商户（开发计划 M4）；回绝按渠道格式回，不写请求记录（有回调记录） */
    CALLBACK("callback", "/pay/callback");

    /** 所有入口共用的包根 */
    public static final String API_PACKAGE = "ai.neargo.shop.pay.api";

    private final String subPackage;
    private final String prefix;

    PortalFace(String subPackage, String prefix) {
        this.subPackage = subPackage;
        this.prefix = prefix;
    }

    /** 本入口的 controller 必须住的包，例如 {@code ai.neargo.shop.pay.api.ops} */
    public String packageName() {
        return API_PACKAGE + "." + subPackage;
    }

    public String prefix() {
        return prefix;
    }

    /** 按包名找入口：{@code ai.neargo.shop.pay.api.ops.refund} 属于 {@link #OPS} */
    public static Optional<PortalFace> ofPackage(String packageName) {
        return Arrays.stream(values())
                .filter(f -> packageName.equals(f.packageName()) || packageName.startsWith(f.packageName() + "."))
                .findFirst();
    }

    /** 路径是否落在本入口下：等于前缀，或以「前缀 + /」开头（{@code /biz/payment} 不算 {@code /biz/pay}） */
    public boolean owns(String path) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
