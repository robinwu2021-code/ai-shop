package ai.neargo.shop.merchant.paysync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 门店同步到支付系统（near-pay）的配置。默认关闭。
 *
 * @param enabled       总开关
 * @param baseUrl       near-pay 内部地址（同机部署为 http://127.0.0.1:18093）
 * @param caller        X-Internal-Caller，须在 near-pay {@code near-pay.platmch.source-callers} 登记
 * @param credential    X-Internal-Credential（调用方专属凭据；near-pay 只存其 SHA-256：{@code shop.pay.internal-callers.<caller>}）
 * @param sourceScope   来源隔离域
 * @param defaultMarket 商户未设市场时使用
 * @param mediaBaseUrl  媒体为相对路径时拼接的公网 https 前缀；为空则不发送相对路径的 Logo
 * @param scanInterval  变更扫描间隔
 * @param timeout       单次调用超时
 * @param fundsPath     推给 near-pay 的门店资金路径（DIRECT 直付 / AGGREGATED 归集），默认 DIRECT。
 *                      near-pay 解析门店收款时要求二者之一，空就一律拒绝（PAY_FORBIDDEN）。
 *                      2026-11-06 用户决定「目前默认都是直付」：不读 ai-shop 主体的 funds_mode（那个字段还管 B 端价格与结算）
 */
@ConfigurationProperties(prefix = "shop.nearpay.store-sync")
public record StoreSyncProperties(boolean enabled, String baseUrl, String caller, String credential, String sourceScope, String defaultMarket,
                                  String mediaBaseUrl, Duration scanInterval, Duration timeout, String fundsPath) {
    public StoreSyncProperties(boolean enabled, String baseUrl, String caller, String credential, String sourceScope,
                               String defaultMarket, String mediaBaseUrl, Duration scanInterval, Duration timeout) {
        this(enabled, baseUrl, caller, credential, sourceScope, defaultMarket, mediaBaseUrl, scanInterval, timeout, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public StoreSyncProperties {
        fundsPath = blank(fundsPath) ? "DIRECT" : fundsPath.trim().toUpperCase(java.util.Locale.ROOT);
        if (!fundsPath.equals("DIRECT") && !fundsPath.equals("AGGREGATED")) {
            throw new IllegalArgumentException("shop.nearpay.store-sync.funds-path 须为 DIRECT / AGGREGATED：" + fundsPath);
        }
        caller = blank(caller) ? "AI_SHOP_UME" : caller;
        sourceScope = blank(sourceScope) ? "MAIN" : sourceScope;
        defaultMarket = blank(defaultMarket) ? "TW" : defaultMarket;
        scanInterval = scanInterval == null ? Duration.ofSeconds(60) : scanInterval;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @Override
    public String toString() {
        return "StoreSyncProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", caller=" + caller + ", scope=" + sourceScope + "]";
    }
}
