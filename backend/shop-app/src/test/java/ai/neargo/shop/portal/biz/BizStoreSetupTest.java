package ai.neargo.shop.portal.biz;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.foundation.FoundationQueryFacade.BusinessType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.pay.MarketPort;

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
}
