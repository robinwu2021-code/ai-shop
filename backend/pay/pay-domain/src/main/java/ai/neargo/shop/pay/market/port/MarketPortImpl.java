package ai.neargo.shop.pay.market.port;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.spi.pay.MarketPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@link MarketPort} 实现。只做形状转换，判断都在 {@link MarketService} */
@Component
public class MarketPortImpl implements MarketPort {

    private final MarketService markets;

    public MarketPortImpl(MarketService markets) {
        this.markets = markets;
    }

    @Override
    public List<MarketBrief> all() {
        return markets.all().stream().map(MarketPortImpl::toBrief).toList();
    }

    @Override
    public Optional<MarketBrief> find(String market) {
        return markets.find(market).map(MarketPortImpl::toBrief);
    }

    @Override
    public void saveRate(String market, double displayRate, boolean enabled, String operatorNo) {
        markets.saveRate(market, displayRate, enabled, operatorNo);
    }

    private static MarketBrief toBrief(MarketService.MarketRow m) {
        return new MarketBrief(m.market(), m.name(), m.currency(), m.currencyScale(),
                m.timeZone(), m.displayRate(), m.enabled());
    }
}
