package ai.neargo.shop.portal.biz;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.BusinessType;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.platform.port.GeoRouter;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.platform.GeoPort;
import ai.neargo.shop.user.service.AuthService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** TDD-B端建店默认经营市场 AC1–AC4: one deployment market, default one-to-one industry mapping. */
class BizStoreSetupTest {
    static MarketPort markets(boolean twEnabled) {
        var all = Map.of(
                "CN", new MarketPort.MarketBrief("CN", "中国大陆", "CNY", 2, "Asia/Shanghai", 1, true),
                "TW", new MarketPort.MarketBrief("TW", "台湾", "TWD", 2, "Asia/Taipei", 4.4, twEnabled));
        return new MarketPort() {
            public List<MarketBrief> all() { return List.copyOf(all.values()); }
            public Optional<MarketBrief> find(String m) { return Optional.ofNullable(all.get(m)); }
            public void saveRate(String m, double r, boolean e, String o) { throw new AssertionError(); }
        };
    }

    static BizMerchantController.QuickStartReq req(String market, String industry, String sub) {
        return new BizMerchantController.QuickStartReq("店", "地址", "RETAIL", market, industry, sub);
    }

    @Test
    void storeSetupListsOnlyTheDeploymentMarketWithPublishedTypesMappedOneToOne() {
        var tw = markets(true).find("TW").orElseThrow();
        var setup = BizFoundationController.storeSetup(tw, List.of(new BusinessType("RETAIL", "零售", 3)));
        assertEquals(1, setup.markets().size());
        var m = setup.markets().getFirst();
        assertEquals(List.of("TW", "TWD", 2, "Asia/Taipei"),
                List.of(m.market(), m.currency(), m.currencyScale(), m.timeZone()));
        var sub = m.industries().getFirst().subIndustries().getFirst();
        assertEquals("RETAIL", m.industries().getFirst().industryCode());
        assertEquals(List.of("RETAIL", "RETAIL", 3L),
                List.of(sub.subIndustryCode(), sub.businessTypeCode(), sub.catalogVersion()));
    }

    @Test
    void quickStartUsesTheDeploymentMarketAndRejectsAnythingElse() {
        var twOn = markets(true);
        assertEquals("TW", BizMerchantController.resolveMarket("TW", twOn, req(null, null, null)));
        assertEquals("TW", BizMerchantController.resolveMarket("TW", twOn, req("TW", "RETAIL", "RETAIL")));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", twOn, req("CN", null, null)));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", twOn, req("TW", "FOOD", "RETAIL")));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", markets(false), req("TW", null, null)));
    }

    @Test
    void undeployedDefaultKeepsTheLegacyChinaBehaviour() {
        assertEquals("CN", BizMerchantController.resolveMarket("CN", markets(false), req(null, null, null)));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("CN", markets(true), req("TW", null, null)));
    }

    // ---------------------------------------------------------------- TDD-注册地区与三步建店

    static BizMerchantController.QuickStartReq other(String type, String industryText, String subText) {
        return new BizMerchantController.QuickStartReq("店", "地址", type, null, "OTHER", "OTHER",
                industryText, subText, null);
    }

    static BizMerchantController.QuickStartReq subOther(String type, String industry, String subText) {
        return new BizMerchantController.QuickStartReq("店", "地址", type, null, industry, "OTHER",
                null, subText, null);
    }

    @Test
    void ac5StoreSetupCarriesMapProviderAndThePublishedOtherBusinessType() {
        var cn = markets(true).find("CN").orElseThrow();
        var types = List.of(new BusinessType("RETAIL", "零售", 3), new BusinessType("FOOD", "餐饮", 1));
        var amap = new GeoRouter.ClientMap(GeoRouter.AMAP, "amap-js", "amap-sec", GeoPort.GCJ02);

        var withMap = BizFoundationController.storeSetup(cn, types, amap, "RETAIL");
        assertEquals("AMAP", withMap.markets().getFirst().mapProvider());
        assertEquals("RETAIL", withMap.otherBusinessTypeCode());

        assertNull(BizFoundationController.storeSetup(cn, types, null, "RETAIL").markets().getFirst().mapProvider(),
                "没有可用的端上地图时不给地图选点");
        assertNull(BizFoundationController.storeSetup(cn, types, amap, "SERVICE").otherBusinessTypeCode(),
                "配置的兜底经营类型未发布就不提供顶层「其他」");
        assertNull(BizFoundationController.storeSetup(cn, types, amap, "").otherBusinessTypeCode());
    }

    // ---------------------------------------------------------------- TDD-双地图提供方与门店选点

    @Test
    void mapsAc2StoreSetupCarriesTheMarketsProviderAndItsBrowserKeys() {
        var tw = markets(true).find("TW").orElseThrow();
        var cn = markets(true).find("CN").orElseThrow();
        var types = List.of(new BusinessType("RETAIL", "零售", 3));

        var google = BizFoundationController.storeSetup(tw, types,
                new GeoRouter.ClientMap(GeoRouter.GOOGLE, "google-js", null, GeoPort.WGS84), null)
                .markets().getFirst();
        assertEquals(List.of("GOOGLE", "google-js"), List.of(google.mapProvider(), google.mapJsKey()));
        assertNull(google.mapJsSecurity(), "安全密钥只有高德有");

        var amap = BizFoundationController.storeSetup(cn, types,
                new GeoRouter.ClientMap(GeoRouter.AMAP, "amap-js", "amap-sec", GeoPort.GCJ02), null)
                .markets().getFirst();
        assertEquals(List.of("AMAP", "amap-js", "amap-sec"),
                List.of(amap.mapProvider(), amap.mapJsKey(), amap.mapJsSecurity()));

        var none = BizFoundationController.storeSetup(tw, types, null, null).markets().getFirst();
        assertNull(none.mapProvider());
        assertNull(none.mapJsKey());
        assertNull(none.mapJsSecurity());
    }

    static BizMerchantController.QuickStartReq located(Integer lat, Integer lng, String sys) {
        return new BizMerchantController.QuickStartReq("店", "地址", null, null, null, null,
                null, null, null, lat, lng, sys);
    }

    @Test
    void mapsAc5QuickStartCoordinatesAreBothOrNeitherInRangeWithAKnownCoordSys() {
        var on = markets(true);
        assertEquals("TW",
                BizMerchantController.resolveMarket("TW", on, located(25_033964, 121_564472, "wgs84"), null));
        assertEquals("TW",
                BizMerchantController.resolveMarket("TW", on, located(-90_000000, 180_000000, "gcj02"), null));
        assertEquals("TW", BizMerchantController.resolveMarket("TW", on, located(null, null, null), null));
        // 只给一半、越界、坐标系缺失或不认识：都拒
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(25_033964, null, "wgs84"), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(null, 121_564472, "wgs84"), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(90_000001, 0, "wgs84"), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(0, -180_000001, "wgs84"), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(25_033964, 121_564472, null), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, located(25_033964, 121_564472, "bd09"), null));
    }

    @Test
    void mapsAc5QuickStartCommandCarriesCoordinatesOnlyWhenGiven() {
        var cmd = BizMerchantController.quickStartCommand("U1", "TW", located(25_033964, 121_564472, "wgs84"));
        assertEquals(List.of(25_033964, 121_564472, "wgs84"), List.of(cmd.latE6(), cmd.lngE6(), cmd.coordSys()));

        var bare = BizMerchantController.quickStartCommand("U1", "TW", located(null, null, "wgs84"));
        assertNull(bare.latE6());
        assertNull(bare.lngE6());
        assertNull(bare.coordSys(), "没有坐标时坐标系不落库");
    }

    @Test
    void ac4ac5StoreSetupMarketIsTheAccountsAndStaffFallBackToTheDeploymentDefault() {
        var auth = mock(AuthService.class);
        when(auth.accountMarket("U1")).thenReturn("TW");
        var owner = new LoginUser(Realm.MERCHANT, SubjectKind.USR, "U1", "o", List.of(), List.of(), null, null);
        var staff = new LoginUser(Realm.MERCHANT, SubjectKind.MCH, "MA1", "s", List.of(), List.of(), null, null);
        assertEquals("TW", BizFoundationController.accountMarketOf(owner, auth, "CN"));
        assertEquals("CN", BizFoundationController.accountMarketOf(staff, auth, "CN"));
        assertEquals("CN", BizFoundationController.accountMarketOf(owner, null, "CN"));
    }

    @Test
    void ac6QuickStartMarketIsTheAccountMarket() {
        var on = markets(true);
        assertEquals("TW", BizMerchantController.resolveMarket("TW", on, req(null, null, null)));
        assertEquals("CN", BizMerchantController.resolveMarket("CN", on, req("CN", null, null)));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", on, req("CN", null, null)));
    }

    @Test
    void ac7TopLevelOtherNeedsBothTextsAndTheConfiguredBusinessType() {
        var on = markets(true);
        assertEquals("TW", BizMerchantController.resolveMarket("TW", on, other("RETAIL", "花艺", "永生花"), "RETAIL"));
        // 文字缺一、经营类型不等于兜底、没配兜底、子业态不是 OTHER：都拒
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, other("RETAIL", " ", "永生花"), "RETAIL"));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, other("RETAIL", "花艺", null), "RETAIL"));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, other("FOOD", "花艺", "永生花"), "RETAIL"));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, other("RETAIL", "花艺", "永生花"), null));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", on,
                new BizMerchantController.QuickStartReq("店", "地址", "RETAIL", null, "OTHER", "RETAIL",
                        "花艺", "永生花", null), "RETAIL"));
        // ≤20 字（按字符，不按字节）
        assertEquals("TW", BizMerchantController.resolveMarket("TW", on,
                other("RETAIL", "花".repeat(20), " " + "草".repeat(20) + " "), "RETAIL"));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", on,
                other("RETAIL", "花".repeat(21), "永生花"), "RETAIL"));
    }

    @Test
    void ac8SubIndustryOtherNeedsTextAndKeepsTheDefaultMapping() {
        var on = markets(true);
        assertEquals("TW", BizMerchantController.resolveMarket("TW", on, subOther("RETAIL", "RETAIL", "文具"), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, subOther("RETAIL", "RETAIL", ""), null));
        assertThrows(BizException.class,
                () -> BizMerchantController.resolveMarket("TW", on, subOther("RETAIL", "FOOD", "文具"), null));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", on,
                subOther("RETAIL", "RETAIL", "文".repeat(21)), null));
    }

    @Test
    void ac9AddressDetailIsAtMost64AndTextsReachTheCommandOnlyForOther() {
        var on = markets(true);
        var longDetail = new BizMerchantController.QuickStartReq("店", "地址", null, null, null, null,
                null, null, "门".repeat(65));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", on, longDetail, null));

        var cmd = BizMerchantController.quickStartCommand("U1", "TW",
                new BizMerchantController.QuickStartReq(" 店 ", "地址", "RETAIL", null, "OTHER", "OTHER",
                        " 花艺 ", " 永生花 ", " 3F "));
        assertEquals(List.of("U1", "TW", "RETAIL", "花艺", "永生花", "3F"), List.of(cmd.ownerUserNo(), cmd.market(),
                cmd.businessTypeCode(), cmd.industryText(), cmd.subIndustryText(), cmd.addressDetail()));

        var mapped = BizMerchantController.quickStartCommand("U1", "TW",
                new BizMerchantController.QuickStartReq("店", "地址", "RETAIL", null, "RETAIL", "RETAIL",
                        "顺手填的", "也是", "  "));
        assertNull(mapped.industryText(), "不是「其他」时不存自填文字");
        assertNull(mapped.subIndustryText());
        assertNull(mapped.addressDetail());
    }
}
