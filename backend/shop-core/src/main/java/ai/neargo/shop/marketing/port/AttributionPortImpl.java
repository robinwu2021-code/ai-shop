package ai.neargo.shop.marketing.port;

import ai.neargo.shop.marketing.attribution.AttributionService;

import ai.neargo.shop.spi.marketing.AttributionPort;
import org.springframework.stereotype.Component;

/**
 * {@link AttributionPort} 实现（M6a 起为**真实实现**，此前是恒返回 PLATFORM 的占位，R4）。
 *
 * <p>下单时被 trade 调用，把 {@code trafficSource} **固化进子订单** —— 之后无论用户
 * 再扫谁的码，这一单的费率档都不变（TDD-backend §7.4）。
 */
@Component
public class AttributionPortImpl implements AttributionPort {

    private final AttributionService attributionService;

    public AttributionPortImpl(AttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @Override
    public String attributedMerchant(String userNo) {
        var current = attributionService.current(userNo);
        return current == null ? null : current.merchantNo();
    }

    @Override
    public String resolveTrafficSource(String userNo, String merchantNo) {
        var current = attributionService.current(userNo);
        if (current == null) {
            return PLATFORM;
        }
        // **必须是「归属到这家店」才算自带客流**：用户归属 A 店却在 B 店下单时，
        // 对 B 店而言这是平台带来的客户，不能让 B 店蹭 A 店的零佣金档
        return MERCHANT_OWNED.equals(current.trafficSource())
                && merchantNo != null && merchantNo.equals(current.merchantNo())
                ? MERCHANT_OWNED : PLATFORM;
    }
}
