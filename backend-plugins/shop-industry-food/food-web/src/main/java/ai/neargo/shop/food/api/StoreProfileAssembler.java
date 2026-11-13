package ai.neargo.shop.food.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.StoreProfileVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * 组装门店基本信息：门店卡（平台）+ 营业策略（餐饮，嵌入本地 / 拆分远程）+ 市场币种（平台）。
 * 放在 food-web 是因为市场与商家两个平台端口只在宿主进程里；拆分部署时 food-web 也跑在宿主。
 */
public class StoreProfileAssembler {
    private final StoreDirectoryPort stores;
    private final StoreOperationService policies;
    private final Supplier<MerchantQueryPort> merchants;
    private final Supplier<MarketPort> markets;
    private final Clock clock;

    public StoreProfileAssembler(StoreDirectoryPort stores, StoreOperationService policies,
            Supplier<MerchantQueryPort> merchants, Supplier<MarketPort> markets, Clock clock) {
        this.stores = stores;
        this.policies = policies;
        this.merchants = merchants;
        this.markets = markets;
        this.clock = clock;
    }

    public StoreProfileVO profile(String entityNo, String storeNo) {
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null || !entityNo.equals(card.entityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        Instant now = clock.instant();
        var policy = policies.getPolicy(entityNo, storeNo, new GetPolicyQuery(now));
        if (policy == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        ZoneId zone = ZoneId.of(policy.timezone());
        LocalDate date = businessDate(now, zone, policy.cutoverMinute());
        Instant starts = date.atStartOfDay(zone).plusMinutes(policy.cutoverMinute()).toInstant();

        String market = null;
        String currency = null;
        Integer scale = null;
        MerchantQueryPort merchantPort = merchants.get();
        MarketPort marketPort = markets.get();
        if (merchantPort != null && marketPort != null) {
            market = merchantPort.marketOf(entityNo);
            var brief = market == null ? null : marketPort.find(market).orElse(null);
            if (brief != null) {
                currency = brief.currency();
                scale = brief.currencyScale();
            } else {
                market = null; // 市场码查不到：三项一起为空，不给半截事实
            }
        }
        return new StoreProfileVO(card.storeNo(), card.storeName(), card.entityNo(), card.status(),
                market, currency, scale, policy.timezone(), policy.cutoverMinute(), policy.payPolicy(),
                date, starts, starts.plus(Duration.ofDays(1)), now);
    }

    /** 门店时区的当前时刻减切日分钟取日期：上海切日 04:00 时，凌晨 02:00 仍属前一营业日。 */
    static LocalDate businessDate(Instant now, ZoneId zone, int cutoverMinute) {
        return now.atZone(zone).minusMinutes(cutoverMinute).toLocalDate();
    }
}
