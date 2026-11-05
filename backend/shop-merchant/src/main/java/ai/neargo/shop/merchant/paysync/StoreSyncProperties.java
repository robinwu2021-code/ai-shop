package ai.neargo.shop.merchant.paysync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 门店同步到支付系统（near-pay）的配置。默认关闭。
 *
 * @param enabled       总开关
 * @param baseUrl       near-pay 内部地址（同机部署为 http://127.0.0.1:18093）
 * @param caller        X-Internal-Caller，须在 near-pay {@code near-pay.platmch.source-callers} 登记
 * @param sourceScope   来源隔离域
 * @param defaultMarket 商户未设市场时使用
 * @param mediaBaseUrl  媒体为相对路径时拼接的公网 https 前缀；为空则不发送相对路径的 Logo
 * @param scanInterval  变更扫描间隔
 * @param timeout       单次调用超时
 */
@ConfigurationProperties(prefix = "shop.nearpay.store-sync")
public record StoreSyncProperties(boolean enabled, String baseUrl, String caller, String sourceScope, String defaultMarket,
                                  String mediaBaseUrl, Duration scanInterval, Duration timeout) {
    public StoreSyncProperties {
        caller = blank(caller) ? "AI_SHOP_UME" : caller;
        sourceScope = blank(sourceScope) ? "MAIN" : sourceScope;
        defaultMarket = blank(defaultMarket) ? "TW" : defaultMarket;
        scanInterval = scanInterval == null ? Duration.ofSeconds(60) : scanInterval;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
