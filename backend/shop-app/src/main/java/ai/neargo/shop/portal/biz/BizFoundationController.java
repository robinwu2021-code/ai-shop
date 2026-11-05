package ai.neargo.shop.portal.biz;

import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.platform.port.GeoRouter;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.user.service.AuthService;
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

    /**
     * @param mapProvider   建店资料页「地图选点」用哪家地图（TDD-双地图提供方与门店选点 AC1/AC2）：按地区
     *                      （缺省 CN→{@code AMAP}，TW/AE→{@code GOOGLE}，{@code shop.geo.provider-by-market} 可改），
     *                      且<b>该家服务端搜索可用、端上 JS key 也配了</b>才给；否则 null，端上只给手动地址
     * @param mapJsKey      端上画地图用的浏览器 JS key（限定包名 / 域名，本来就要下发）；mapProvider 为 null 时为 null
     * @param mapJsSecurity 高德 JS 安全密钥（{@code AMAP} 才有）；其余为 null
     */
    public record StoreSetupMarket(String market, String displayName, String currency, int currencyScale,
                                   String timeZone, List<StoreSetupIndustry> industries, String mapProvider,
                                   String mapJsKey, String mapJsSecurity) {}

    /**
     * @param otherBusinessTypeCode 顶层行业选「其他」时用的经营类型（{@code shop.merchant.other-business-type}）；
     *                              未配置或该类型未发布时为 null，端上不提供顶层「其他」
     */
    public record StoreSetup(List<StoreSetupMarket> markets, String otherBusinessTypeCode) {}

    /** 行业与子业态的「其他」都用这个码；对应的经营类型见 {@link StoreSetup#otherBusinessTypeCode} */
    public static final String OTHER = StoreVO.OTHER;

    /**
     * 首店可选的经营市场（TDD-注册地区与三步建店 AC5）：<b>只有账号的地区</b>，注册时定下，App 里不再切换。
     * 市场未启用则返回空列表，不补造。行业映射为默认的一对一：每个已发布经营类型 T → 行业 T → 子业态 T → 经营类型 T；
     * 两级都可选「其他」（{@link #OTHER}），由端上追加，不在列表里。
     */
    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/merchant/store-setup")
    public StoreSetup storeSetup() {
        var port = markets.getIfAvailable();
        String accountMarket = accountMarketOf(SecurityUtils.requireUser(),
                auth == null ? null : auth.getIfAvailable(), defaultMarket);
        var market = port == null
                ? Optional.<MarketPort.MarketBrief>empty()
                : port.find(accountMarket).filter(MarketPort.MarketBrief::enabled);
        if (market.isEmpty()) return new StoreSetup(List.of(), null);
        var router = geo == null ? null : geo.getIfAvailable();
        var map = router == null ? null : router.clientMapFor(market.get().market()).orElse(null);
        return storeSetup(market.get(), queries.businessTypes(ShopCommerceActorAdapter.current()),
                map, otherBusinessType);
    }

    /**
     * 当前会话的账号地区。店员会话（{@code MCH}）背后不一定有 C 端账号，按部署默认 ——
     * 店员不建首店，这里只是不让他读到别的地区。
     */
    static String accountMarketOf(LoginUser user, AuthService authService, String defaultMarket) {
        if (authService == null || user.realm() != Realm.MERCHANT || user.subjectKind() != SubjectKind.USR)
            return defaultMarket;
        return authService.accountMarket(user.userNo()).trim().toUpperCase(Locale.ROOT);
    }

    /** Default one-to-one industry mapping from the actually published business types. */
    static StoreSetup storeSetup(MarketPort.MarketBrief m, List<BusinessType> types) {
        return storeSetup(m, types, null, null);
    }

    /** @param map 该市场的端上地图（{@link GeoRouter#clientMapFor}）；null = 没有地图选点 */
    static StoreSetup storeSetup(MarketPort.MarketBrief m, List<BusinessType> types, GeoRouter.ClientMap map,
                                 String otherBusinessType) {
        var industries = types.stream()
                .map(t -> new StoreSetupIndustry(t.businessTypeCode(), t.displayName(), List.of(
                        new StoreSetupSubIndustry(t.businessTypeCode(), t.displayName(), t.businessTypeCode(),
                                t.displayName(), t.catalogVersion()))))
                .toList();
        String other = otherBusinessType == null || otherBusinessType.isBlank() ? null
                : types.stream().map(BusinessType::businessTypeCode)
                        .filter(otherBusinessType.trim()::equals).findFirst().orElse(null);
        return new StoreSetup(List.of(new StoreSetupMarket(
                m.market(), m.name(), m.currency(), m.currencyScale(), m.timeZone(), industries,
                map == null ? null : map.provider(), map == null ? null : map.jsKey(),
                map == null ? null : map.jsSecurity())),
                other);
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
    private ObjectProvider<AuthService> auth;
    private ObjectProvider<GeoRouter> geo;
    private String otherBusinessType = "";

    /** 账号地区与地图能力用 setter 注入：构造参数是测试按位置传的，见 BizStoreSetupTest */
    @Autowired
    void setAccountMarketSources(ObjectProvider<AuthService> auth, ObjectProvider<GeoRouter> geo) {
        this.auth = auth;
        this.geo = geo;
    }

    @Value("${shop.merchant.other-business-type:}")
    void setOtherBusinessType(String code) {
        this.otherBusinessType = code == null ? "" : code.trim();
    }

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
