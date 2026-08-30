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
    private final ai.neargo.shop.marketing.attribution.FissionInviteService fissionInviteService;

    public AttributionPortImpl(
            AttributionService attributionService,
            ai.neargo.shop.marketing.attribution.FissionInviteService fissionInviteService) {
        this.attributionService = attributionService;
        this.fissionInviteService = fissionInviteService;
    }

    /**
     * 首单回填。**吞掉异常**：这是统计口径，不是交易的一部分 ——
     * 让一次营销统计失败去回滚一笔已经收了钱的订单，代价方向完全反了。
     */
    @Override
    public void onFirstOrder(String userNo, String orderNo) {
        try {
            fissionInviteService.onFirstOrder(userNo, orderNo);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AttributionPortImpl.class)
                    .warn("[裂变] 首单回填失败 user={} order={}：{}", userNo, orderNo, e.toString());
        }
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
