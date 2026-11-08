package ai.neargo.shop.common;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 门店号 → 这家店<b>按哪个市场经营</b>（所属主体的 {@code mch_entity.market}），为空时按部署默认
 * {@code shop.merchant.default-market} 回落（TDD-商户App快递公司 §2 校验口径）。
 *
 * <p>为什么单独一处：快递公司清单、发货、改运单、发货设置四个入口都要按同一口径取市场，
 * 而 {@link MerchantQueryPort#storeProfileOf} 给的是<b>原值</b>（为空不回落，核验要事实）。
 * 回落逻辑散在四处，迟早有一处忘了回落 —— 存量大陆主体大多没写过市场，忘了的那一处会给它空表、拒掉所有发货。
 *
 * <p><b>调用方传的必须是订单 / 门店自己的门店号</b>，不是调用方上下文里的：叫快递取件回填运单时，
 * 调用方的门店号是空的。
 */
@Component
public class StoreMarkets {

    private final ObjectProvider<MerchantQueryPort> merchants;
    private final String defaultMarket;

    public StoreMarkets(ObjectProvider<MerchantQueryPort> merchants,
                        @Value("${shop.merchant.default-market:CN}") String defaultMarket) {
        this.merchants = merchants;
        this.defaultMarket = defaultMarket.trim().toUpperCase(Locale.ROOT);
    }

    /** 已回落的市场码（大写）；门店查不到也按部署默认 */
    public String of(String storeNo) {
        MerchantQueryPort port = merchants.getIfAvailable();
        return Optional.ofNullable(port).flatMap(p -> p.storeProfileOf(storeNo))
                .map(MerchantQueryPort.StoreProfile::market)
                .filter(m -> !m.isBlank())
                .map(m -> m.trim().toUpperCase(Locale.ROOT))
                .orElse(defaultMarket);
    }
}
