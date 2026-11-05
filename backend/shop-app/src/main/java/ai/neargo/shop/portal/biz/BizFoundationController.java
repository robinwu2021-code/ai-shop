package ai.neargo.shop.portal.biz;

import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.pay.MarketPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import java.util.Optional;
import java.util.Locale;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.*;
import ai.neargo.shop.commercebridge.ShopCommerceActorAdapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class BizFoundationController {
    private final FoundationQueryFacade queries;
    private final ObjectProvider<MarketPort> markets;
    private final String defaultMarket;

    public BizFoundationController(
            FoundationQueryFacade queries,
            ObjectProvider<MarketPort> markets,
            @Value("${shop.merchant.default-market:CN}") String defaultMarket) {
        this.queries = queries;
        this.markets = markets;
        this.defaultMarket = defaultMarket.trim().toUpperCase(Locale.ROOT);
    }

    public record StoreSetupSubIndustry(String subIndustryCode, String displayName, String businessTypeCode,
                                        String businessTypeDisplayName, long catalogVersion) {}

    public record StoreSetupIndustry(String industryCode, String displayName,
                                     List<StoreSetupSubIndustry> subIndustries) {}

    public record StoreSetupMarket(String market, String displayName, String currency, int currencyScale,
                                   String timeZone, List<StoreSetupIndustry> industries) {}

    public record StoreSetup(List<StoreSetupMarket> markets) {}

    /**
     * 首店可选的经营市场（TDD-B端建店默认经营市场）。本部署只有一个市场；未启用则返回空列表，
     * 不补造。行业映射为默认的一对一：每个已发布经营类型 T → 行业 T → 子业态 T → 经营类型 T。
     */
    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/merchant/store-setup")
    public StoreSetup storeSetup() {
        var port = markets.getIfAvailable();
        var market = port == null
                ? Optional.<MarketPort.MarketBrief>empty()
                : port.find(defaultMarket).filter(MarketPort.MarketBrief::enabled);
        if (market.isEmpty()) return new StoreSetup(List.of());
        return storeSetup(market.get(), queries.businessTypes(ShopCommerceActorAdapter.current()));
    }

    /** Default one-to-one industry mapping from the actually published business types. */
    static StoreSetup storeSetup(MarketPort.MarketBrief m, List<BusinessType> types) {
        var industries = types.stream()
                .map(t -> new StoreSetupIndustry(t.businessTypeCode(), t.displayName(), List.of(
                        new StoreSetupSubIndustry(t.businessTypeCode(), t.displayName(), t.businessTypeCode(),
                                t.displayName(), t.catalogVersion()))))
                .toList();
        return new StoreSetup(List.of(new StoreSetupMarket(
                m.market(), m.name(), m.currency(), m.currencyScale(), m.timeZone(), industries)));
    }

    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/business-types")
    public List<BusinessType> types() {
        return queries.businessTypes(ShopCommerceActorAdapter.current());
    }

    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/business-types/{typeCode}/plans")
    public PlanCatalog plans(@PathVariable String typeCode) {
        return queries.plans(ShopCommerceActorAdapter.current(), typeCode);
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/context")
    public HostStoreContext context(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        var context = queries.store(actor, new StoreRef(actor.tenantNo(), storeNo));
        // 宿主字段（TDD-B端建店默认经营市场 AC6）：门店名/地址/主体市场与市场元数据，取不到为空
        var port = merchants == null ? null : merchants.getIfAvailable();
        var profile = port == null ? Optional.<MerchantQueryPort.StoreProfile>empty()
                : port.storeProfileOf(context.storeNo());
        var marketPort = markets.getIfAvailable();
        var market = marketPort == null ? Optional.<MarketPort.MarketBrief>empty()
                : profile.map(MerchantQueryPort.StoreProfile::market)
                        .flatMap(marketPort::find);
        return HostStoreContext.of(context, profile, market);
    }

    private ObjectProvider<MerchantQueryPort> merchants;

    @Autowired
    void setMerchants(ObjectProvider<MerchantQueryPort> merchants) {
        this.merchants = merchants;
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/subscription")
    public Subscription subscription(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        return queries.subscription(actor, new StoreRef(actor.tenantNo(), storeNo));
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/entitlements")
    public Entitlements entitlements(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        return queries.entitlements(actor, new StoreRef(actor.tenantNo(), storeNo));
    }
}
