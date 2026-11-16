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
import org.junit.jupiter.api.DisplayName;
import java.util.Locale;

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

    // ---------------------------------------------------------------- TDD-子行业与能力端验收

    static ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow row(String industry, String sub, String type,
                                                                    String zh, String tw, int sort) {
        return new ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow(industry, sub, type, zh, tw, null, sort);
    }

    static final List<ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow> ROWS = List.of(
            row("FOOD", null, "FOOD", "餐饮", "餐飲", 1),
            row("FOOD", "FOOD_BREAKFAST", "FOOD", "早餐店", "早餐店", 1),
            row("FOOD", "FOOD_CAFE", "FOOD", "咖啡厅", "咖啡廳", 2),
            row("RETAIL", null, "RETAIL", "零售", "零售", 2),
            row("RETAIL", "RETAIL_CVS", "RETAIL", "便利店 / 超市", "便利商店 / 超市", 1),
            row("SERVICE", null, "SERVICE", "服务业", "服務業", 3),
            row("SERVICE", "SERVICE_HAIR", "SERVICE", "美发", "美髮", 1));

    static final List<BusinessType> FOOD_AND_RETAIL =
            List.of(new BusinessType("RETAIL", "零售", 3), new BusinessType("FOOD", "餐飲", 1));

    @Test
    @DisplayName("B1 两级树：只列已发布经营类型的子行业，行业下没有可用子行业就不列")
    void b1StoreSetupListsRealSubIndustriesOfPublishedTypesOnly() {
        var tw = markets(true).find("TW").orElseThrow();
        var setup = BizFoundationController.storeSetup(tw, FOOD_AND_RETAIL, null, null, ROWS, Locale.TAIWAN);
        var industries = setup.markets().getFirst().industries();
        assertEquals(List.of("FOOD", "RETAIL"),
                industries.stream().map(BizFoundationController.StoreSetupIndustry::industryCode).toList(),
                "SERVICE 未发布，整个行业不列");
        var food = industries.getFirst();
        assertEquals("餐飲", food.displayName());
        assertEquals(List.of("FOOD_BREAKFAST", "FOOD_CAFE"),
                food.subIndustries().stream().map(BizFoundationController.StoreSetupSubIndustry::subIndustryCode)
                        .toList());
        var cafe = food.subIndustries().get(1);
        assertEquals(List.of("咖啡廳", "FOOD", "餐飲", 1L),
                List.of(cafe.displayName(), cafe.businessTypeCode(), cafe.businessTypeDisplayName(),
                        cafe.catalogVersion()));
    }

    @Test
    @DisplayName("B2 行业表为空：回落一对一默认映射，与改动前一致")
    void b2EmptyCatalogFallsBackToTheDefaultMapping() {
        var tw = markets(true).find("TW").orElseThrow();
        assertEquals(BizFoundationController.storeSetup(tw, FOOD_AND_RETAIL, null, null),
                BizFoundationController.storeSetup(tw, FOOD_AND_RETAIL, null, null, List.of(), Locale.TAIWAN));
    }

    @Test
    @DisplayName("B3 显示名按语言：繁体取繁体、英文缺省回落简体、其余取简体")
    void b3DisplayNamesFollowTheRequestLanguage() {
        var cafe = row("FOOD", "FOOD_CAFE", "FOOD", "咖啡厅", "咖啡廳", 2);
        assertEquals("咖啡廳", cafe.displayName(Locale.forLanguageTag("zh-TW")));
        assertEquals("咖啡廳", cafe.displayName(Locale.forLanguageTag("zh-Hant-HK")));
        assertEquals("咖啡厅", cafe.displayName(Locale.SIMPLIFIED_CHINESE));
        assertEquals("咖啡厅", cafe.displayName(Locale.ENGLISH), "没有英文名时回落简体");
        assertEquals("Cafe", new ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow(
                "FOOD", "FOOD_CAFE", "FOOD", "咖啡厅", "咖啡廳", "Cafe", 2).displayName(Locale.ENGLISH));
    }

    static BizMerchantController.QuickStartReq pick(String type, String industry, String sub) {
        return new BizMerchantController.QuickStartReq("店", "地址", type, "TW", industry, sub);
    }

    @Test
    @DisplayName("B4/B5 建店校验：子行业须属于该行业、经营类型须是子行业的；旧形状与「其他」规则照旧")
    void b4QuickStartValidatesSubIndustryAgainstTheCatalog() {
        var tw = markets(true);
        assertEquals("TW", BizMerchantController.resolveMarket("TW", tw, pick("FOOD", "FOOD", "FOOD_CAFE"), null, ROWS));
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", tw,
                pick("FOOD", "RETAIL", "FOOD_CAFE"), null, ROWS), "子行业不属于该行业");
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", tw,
                pick("RETAIL", "FOOD", "FOOD_CAFE"), null, ROWS), "经营类型不是子行业的");
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", tw,
                pick("FOOD", "FOOD", "FOOD_UNKNOWN"), null, ROWS), "未登记的子行业");
        assertEquals("TW", BizMerchantController.resolveMarket("TW", tw, pick("FOOD", "FOOD", "FOOD"), null, ROWS),
                "旧形状：行业 = 子行业 = 经营类型");
        assertEquals("TW", BizMerchantController.resolveMarket("TW", tw,
                new BizMerchantController.QuickStartReq("店", "地址", "FOOD", "TW", "FOOD", "OTHER", null, "饭团",
                        null), null, ROWS), "已登记行业 + 子行业「其他」");
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", tw,
                new BizMerchantController.QuickStartReq("店", "地址", "RETAIL", "TW", "FOOD", "OTHER", null, "饭团",
                        null), null, ROWS), "子行业「其他」时经营类型仍须是行业的");
    }

    @Test
    @DisplayName("B4/B5 落库的行业码：登记的两级都存；子行业「其他」只存行业；旧形状与顶层「其他」都不存")
    void b4StoredCodesFollowWhatWasActuallyChosen() {
        assertEquals(java.util.Arrays.asList("FOOD", "FOOD_CAFE"),
                BizMerchantController.storedIndustry(pick("FOOD", "FOOD", "FOOD_CAFE"), ROWS));
        assertEquals(java.util.Arrays.asList("FOOD", null), BizMerchantController.storedIndustry(
                new BizMerchantController.QuickStartReq("店", "地址", "FOOD", "TW", "FOOD", "OTHER", null, "饭团",
                        null), ROWS));
        assertEquals(java.util.Arrays.asList(null, null),
                BizMerchantController.storedIndustry(pick("FOOD", "FOOD", "FOOD"), ROWS));
        assertEquals(java.util.Arrays.asList(null, null),
                BizMerchantController.storedIndustry(other("RETAIL", "花艺", "永生花"), ROWS));
        assertEquals(java.util.Arrays.asList(null, null),
                BizMerchantController.storedIndustry(pick(null, null, null), ROWS));
    }
    @Test void allDisabledConfigurationDoesNotRestoreDefaultIndustries() {
        var disabled = ROWS.stream().map(r -> new ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow(
                r.industryCode(), r.subIndustryCode(), r.businessTypeCode(), r.nameZh(), r.nameTw(), r.nameEn(), r.sort(), false)).toList();
        var setup = BizFoundationController.storeSetup(markets(true).find("TW").orElseThrow(),
                FOOD_AND_RETAIL, null, null, disabled, Locale.TRADITIONAL_CHINESE);
        assertTrue(setup.markets().getFirst().industries().isEmpty());
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", markets(true),
                req("TW", "RETAIL", "RETAIL"), null, disabled));
    }

    @Test void disabledChildIsHiddenAndRejectedWithoutChangingOtherChildren() {
        var rows = ROWS.stream().map(r -> new ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow(
                r.industryCode(), r.subIndustryCode(), r.businessTypeCode(), r.nameZh(), r.nameTw(), r.nameEn(), r.sort(),
                !"FOOD_CAFE".equals(r.subIndustryCode()))).toList();
        var setup = BizFoundationController.storeSetup(markets(true).find("TW").orElseThrow(),
                FOOD_AND_RETAIL, null, null, rows, Locale.TRADITIONAL_CHINESE);
        var food = setup.markets().getFirst().industries().stream().filter(r -> r.industryCode().equals("FOOD")).findFirst().orElseThrow();
        assertEquals(List.of("FOOD_BREAKFAST"), food.subIndustries().stream().map(BizFoundationController.StoreSetupSubIndustry::subIndustryCode).toList());
        assertThrows(BizException.class, () -> BizMerchantController.resolveMarket("TW", markets(true),
                new BizMerchantController.QuickStartReq("店", "地址", "FOOD", "TW", "FOOD", "FOOD_CAFE"), null, rows));
        assertEquals("TW", BizMerchantController.resolveMarket("TW", markets(true),
                new BizMerchantController.QuickStartReq("店", "地址", "FOOD", "TW", "FOOD", "FOOD_BREAKFAST"), null, rows));
    }

    @org.junit.jupiter.api.Test
    void operatingEntityNameIsSeparateFromStoreNameAndLegacyCallsStillWork() {
        var req = new BizMerchantController.QuickStartReq("門店一", "地址", "RETAIL", null,
                null, null, null, null, null, null, null, null, "經營實體一");
        var command = BizMerchantController.quickStartCommand("U1", "TW", req);
        org.junit.jupiter.api.Assertions.assertEquals("經營實體一", command.entityName());
        org.junit.jupiter.api.Assertions.assertEquals("門店一", command.storeName());
        var old = new BizMerchantController.QuickStartReq("舊門店", "地址", "RETAIL", null, null, null);
        org.junit.jupiter.api.Assertions.assertNull(BizMerchantController.quickStartCommand("U1", "TW", old).entityName());
    }

}
