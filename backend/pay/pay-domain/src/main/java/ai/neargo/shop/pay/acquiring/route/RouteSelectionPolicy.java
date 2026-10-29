package ai.neargo.shop.pay.acquiring.route;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.entity.PayChannelProduct;
import ai.neargo.shop.pay.channel.entity.PayChannelProductMarket;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMarketMapper;
import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.MerchantCapability;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Route eligibility is the intersection of market, account, product authorization and payment constraints. */
@Component
public class RouteSelectionPolicy {
    public record Selected(PayRoute route, PayChannelProduct product) {
        /** Build the channel identity without asking deployment defaults to replace frozen facts. */
        public ChannelRoute channelRoute() {
            return new ChannelRoute(route.payChannel(), AccessMode.valueOf(route.accessMode()), route.market(),
                    route.currency(), route.payMerchantId(), route.payMerchantNo(), route.subMerchantId(),
                    route.keyVersion());
        }
    }

    private final PayMerchantQueryPort merchants;
    private final PayChannelProductMapper products;
    private final PayChannelProductMarketMapper productMarkets;
    private final MarketService markets;
    private final PayChannelMasterService channelMaster;
    private final LegacyChannelBridge channels;

    public RouteSelectionPolicy(PayMerchantQueryPort merchants, PayChannelProductMapper products,
                                PayChannelProductMarketMapper productMarkets, MarketService markets,
                                PayChannelMasterService channelMaster, LegacyChannelBridge channels) {
        this.merchants = merchants;
        this.products = products;
        this.productMarkets = productMarkets;
        this.markets = markets;
        this.channelMaster = channelMaster;
        this.channels = channels;
    }

    public Optional<Selected> select(Long storeId, String market, String currency, long amountMinor, String payMethod) {
        return available(storeId, market, currency, amountMinor).stream()
                .filter(s -> Objects.equals(s.product().getPayMethod(), payMethod)).findFirst();
    }

    /** Without an order amount only static constraints can be checked. */
    public List<Selected> available(Long storeId, String market) {
        return available(storeId, market, null, null);
    }

    /** Checkout and actual initiation share these same per-order constraints. */
    public List<Selected> available(Long storeId, String market, String currency, Long amountMinor) {
        var region = markets.find(market).filter(MarketService.MarketRow::enabled).orElse(null);
        if (region == null || (currency != null && !currency.equals(region.currency()))
                || (amountMinor != null && amountMinor <= 0)) return List.of();
        String coin = region.currency();
        Set<String> enabled = channelMaster.enabled(market).stream().map(c -> c.getPayChannel())
                .collect(Collectors.toSet());
        List<Selected> out = new ArrayList<>();
        for (PayRoute r : merchants.collectableRoutes(storeId, market)) {
            if (!enabled.contains(r.payChannel()) || !Objects.equals(coin, r.currency())) continue;
            MerchantCapability cap = merchants.capabilityOf(r.payMerchantId()).orElse(null);
            if (cap == null || !"ACTIVE".equals(cap.status())) continue;
            List<PayChannelProduct> candidates = DataScopeContext.executeWithoutScope(() -> products.selectList(
                    Wrappers.<PayChannelProduct>lambdaQuery().eq(PayChannelProduct::getPayChannel, r.payChannel())
                            .eq(PayChannelProduct::getAccessMode, r.accessMode())
                            .eq(PayChannelProduct::getStatus, "ENABLED")
                            .orderByAsc(PayChannelProduct::getSortNo)));
            for (PayChannelProduct p : candidates) {
                if (!cap.payChannelProductIds().contains(p.getId()) || p.getAllowedFundsPaths() == null
                        || Arrays.stream(p.getAllowedFundsPaths().split(",")).map(String::trim)
                                .noneMatch(r.fundsPath()::equals)) continue;
                var row = DataScopeContext.executeWithoutScope(() -> productMarkets.selectOne(
                        Wrappers.<PayChannelProductMarket>lambdaQuery()
                                .eq(PayChannelProductMarket::getPayChannelProductId, p.getId())
                                .eq(PayChannelProductMarket::getMarket, market)
                                .eq(PayChannelProductMarket::getCurrency, coin)
                                .eq(PayChannelProductMarket::getStatus, "ENABLED")));
                Selected selected = new Selected(r, p);
                if (row != null && fits(row, region.currencyScale(), amountMinor)
                        && channels.supportsRoutedAccount(selected.channelRoute())) out.add(selected);
            }
        }
        return out;
    }

    private static boolean fits(PayChannelProductMarket row, int currencyScale, Long amount) {
        if (row.getSingleMinMinor() == null || row.getSingleMaxMinor() == null || row.getAmountScale() == null
                || row.getAmountScale() < 0 || row.getAmountScale() > 3 || currencyScale < 0 || currencyScale > 3) {
            return false;
        }
        if (amount == null) return true;
        if (amount < row.getSingleMinMinor() || (row.getSingleMaxMinor() > 0 && amount > row.getSingleMaxMinor())) {
            return false;
        }
        long divisor = 1;
        for (int i = row.getAmountScale(); i < currencyScale; i++) divisor *= 10;
        return amount % divisor == 0;
    }
}
