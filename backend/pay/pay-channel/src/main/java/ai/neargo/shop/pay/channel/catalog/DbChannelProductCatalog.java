package ai.neargo.shop.pay.channel.catalog;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.entity.PayChannelProduct;
import ai.neargo.shop.pay.channel.entity.PayChannelProductMarket;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMarketMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读 {@code pay_channel_product} × {@code pay_channel_product_market}（db/pay V4）。每次现查 —— 运营停一个产品要立刻生效，
 * 不能等重启；量只有几十行。
 */
public final class DbChannelProductCatalog implements ChannelProductCatalog {

    private static final String ENABLED = "ENABLED";
    private final PayChannelProductMapper products;
    private final PayChannelProductMarketMapper markets;

    public DbChannelProductCatalog(PayChannelProductMapper products, PayChannelProductMarketMapper markets) {
        this.products = products;
        this.markets = markets;
    }

    @Override
    public Set<ChannelKey> enabledPayKeys() {
        Map<Long, List<PayChannelProductMarket>> byProduct = allMarkets().stream()
                .filter(m -> ENABLED.equals(m.getStatus()))
                .collect(Collectors.groupingBy(PayChannelProductMarket::getPayChannelProductId));
        Set<ChannelKey> keys = new HashSet<>();
        for (PayChannelProduct p : allProducts()) {
            if (!ENABLED.equals(p.getStatus())) {
                continue;
            }
            for (PayChannelProductMarket m : byProduct.getOrDefault(p.getId(), List.of())) {
                keys.add(new ChannelKey(m.getMarket(), p.getPayChannel(), AccessMode.valueOf(p.getAccessMode()),
                        sceneOf(p), ChannelOperation.PAY));
            }
        }
        return keys;
    }

    @Override
    public boolean payEnabled(ChannelKey key) {
        List<PayChannelProduct> same = allProducts().stream()
                .filter(p -> p.getPayChannel().equals(key.payChannel())
                        && p.getAccessMode().equals(key.accessMode().name())
                        && sceneOf(p) == key.scene())
                .toList();
        if (same.isEmpty()) {
            return true;
        }
        Set<Long> enabled = same.stream().filter(p -> ENABLED.equals(p.getStatus()))
                .map(PayChannelProduct::getId).collect(Collectors.toSet());
        if (enabled.isEmpty()) {
            return false;
        }
        return allMarkets().stream().anyMatch(m -> enabled.contains(m.getPayChannelProductId())
                && ENABLED.equals(m.getStatus()) && (key.market() == null || key.market().equals(m.getMarket())));
    }

    @Override
    public OptionalInt amountScale(String payChannel, AccessMode accessMode, String market, String currency) {
        Set<Long> ids = allProducts().stream()
                .filter(p -> p.getPayChannel().equals(payChannel) && p.getAccessMode().equals(accessMode.name()))
                .map(PayChannelProduct::getId).collect(Collectors.toSet());
        Set<Integer> scales = allMarkets().stream()
                .filter(m -> ids.contains(m.getPayChannelProductId()))
                .filter(m -> market != null ? market.equals(m.getMarket()) : currency != null && currency.equals(m.getCurrency()))
                .filter(m -> currency == null || currency.equals(m.getCurrency()))
                .map(PayChannelProductMarket::getAmountScale)
                .filter(Objects::nonNull)
                .map(Number::intValue)
                .collect(Collectors.toSet());
        return scales.size() == 1 ? OptionalInt.of(scales.iterator().next()) : OptionalInt.empty();
    }

    @Override
    public int refundIntervalSeconds(String payChannel, AccessMode accessMode) {
        return allProducts().stream()
                .filter(p -> p.getPayChannel().equals(payChannel) && p.getAccessMode().equals(accessMode.name()))
                .map(PayChannelProduct::getRefundIntervalSeconds)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max().orElse(0);
    }

    /** 产品 → 场景。渠道侧方式码决定端上怎么付；认不出时按支付类型兜一个最接近的 */
    static PayScene sceneOf(PayChannelProduct p) {
        return switch (p.getChannelMethod()) {
            case "JSAPI", "APP", "TEST_PAY" -> PayScene.ONLINE_SDK;
            case "NATIVE" -> PayScene.ONLINE_QR;
            default -> switch (p.getPayType()) {
                case "CARD" -> PayScene.IN_STORE_CARD;
                case "OFFLINE" -> PayScene.IN_STORE_SCAN_CUSTOMER;
                default -> PayScene.ONLINE_REDIRECT;
            };
        };
    }

    private List<PayChannelProduct> allProducts() {
        return DataScopeContext.executeWithoutScope(() -> products.selectList(null));
    }

    private List<PayChannelProductMarket> allMarkets() {
        return DataScopeContext.executeWithoutScope(() -> markets.selectList(null));
    }
}
