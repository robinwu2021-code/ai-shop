package ai.neargo.shop.portal.biz;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.CommerceModel;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TDD-B端建店默认经营市场 AC6：门店经营上下文在可移植 commerce 的 StoreContext 上平铺宿主字段，
 * 与 Flutter 契约 CurrentStoreCommercialContext 的可选字段同名。
 */
class HostStoreContextTest {

    private static StoreContext context() {
        return new StoreContext("S1", "E1", CommerceModel.STORE_V1, "RETAIL", "ACTIVE",
                "PENDING_LICENSE", true, List.of("OWNER"), true, 1L, 1L, null, null, null);
    }

    @Test
    @DisplayName("AC6 平铺门店名、地址、市场元数据与默认行业映射，原有字段不变")
    @SuppressWarnings("unchecked")
    void flattensHostFieldsNextToCommerceContext() {
        var json = JsonMapper.builder().build();
        var host = HostStoreContext.of(context(),
                Optional.of(new MerchantQueryPort.StoreProfile("S1", "E1", "Shop", "Road 1", "TW")),
                Optional.of(new MarketPort.MarketBrief("TW", "中国台湾", "TWD", 2, "Asia/Taipei", 4.4, true)));

        Map<String, Object> out = json.convertValue(host, Map.class);

        assertEquals("S1", out.get("storeNo"));
        assertEquals("RETAIL", out.get("businessTypeCode"));
        assertEquals("STORE_V1", out.get("commerceModel"));
        assertEquals("Shop", out.get("storeName"));
        assertEquals("Road 1", out.get("address"));
        assertEquals("TW", out.get("market"));
        assertEquals("TWD", out.get("currency"));
        assertEquals(2, out.get("currencyScale"));
        assertEquals("Asia/Taipei", out.get("timeZone"));
        assertEquals("RETAIL", out.get("industryCode"));
        assertEquals("RETAIL", out.get("subIndustryCode"));
        assertFalse(out.containsKey("context"), "commerce context must be flattened, not nested");
    }

    @Test
    @DisplayName("AC6 取不到门店或市场元数据时不编造")
    void missingHostDataStaysNull() {
        var host = HostStoreContext.of(context(), Optional.empty(), Optional.empty());
        assertNull(host.storeName());
        assertNull(host.market());
        assertNull(host.currency());
        assertNull(host.currencyScale());
        assertEquals("RETAIL", host.industryCode());
    }

    /** TDD-子行业与能力端验收 B6：门店存了行业与子行业码就原样回显；没存回落经营类型 */
    @org.junit.jupiter.api.Test
    void storedSubIndustryIsEchoedOtherwiseTheBusinessType() {
        var stored = HostStoreContext.of(context(),
                Optional.of(new MerchantQueryPort.StoreProfile("S1", "E1", "Shop", "Road 1", "TW", "RETAIL",
                        "RETAIL_CVS")),
                Optional.empty());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("RETAIL", "RETAIL_CVS"),
                java.util.List.of(stored.industryCode(), stored.subIndustryCode()));
        var legacy = HostStoreContext.of(context(),
                Optional.of(new MerchantQueryPort.StoreProfile("S1", "E1", "Shop", "Road 1", "TW")),
                Optional.empty());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("RETAIL", "RETAIL"),
                java.util.List.of(legacy.industryCode(), legacy.subIndustryCode()));
    }
}
