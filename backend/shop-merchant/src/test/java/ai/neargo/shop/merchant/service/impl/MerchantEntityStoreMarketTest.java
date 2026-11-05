package ai.neargo.shop.merchant.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.spi.pay.MarketPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * TDD-B端建店默认经营市场 AC5：门店选择器（{@code GET /biz/stores/mine}）给出每家店的经营市场与经营类型，
 * App 建店后靠「店号 + 主体号 + 市场」认出新店（Flutter 契约 {@code .2-store-setup} 的 Store 可选字段）。
 */
class MerchantEntityStoreMarketTest {

    private static StoreVO store(String businessTypeCode) {
        return new StoreVO("S1", "Shop", "Road", true, "ACTIVE", null, false, 0, false, 0, 0,
                "SELF_OPERATED", false, null, null,
                null, null, null, null, null, null, businessTypeCode);
    }

    private static MerchantEntityServiceImpl service(String entityMarket, StoreVO row, MarketPort markets) {
        var accounts = mock(MerchantMappers.MchAccountMapper.class);
        var entities = mock(MerchantMappers.MchEntityMapper.class);
        var roles = mock(MerchantMappers.MchStoreRoleMapper.class);
        var stores = mock(StoreAdminService.class);
        var account = new MchAccount();
        account.setEntityNo("E1");
        account.setIsOwner(true);
        account.setIsPrimary(true);
        account.setMchAccountNo("A1");
        when(accounts.selectList(any())).thenReturn(List.of(account));
        var entity = new MchEntity();
        entity.setEntityNo("E1");
        entity.setName("Entity");
        entity.setMarket(entityMarket);
        when(entities.selectList(any())).thenReturn(List.of(entity));
        when(stores.list("E1")).thenReturn(List.of(row));
        var service = new MerchantEntityServiceImpl(accounts, entities, roles, stores);
        service.setMarketPort(markets);
        return service;
    }

    @Test
    @DisplayName("AC5 门店带主体市场的币种、小数位、时区，以及经营类型与默认行业映射")
    void storesCarryEntityMarketAndBusinessType() {
        var markets = mock(MarketPort.class);
        when(markets.find("TW")).thenReturn(Optional.of(
                new MarketPort.MarketBrief("TW", "中国台湾", "TWD", 2, "Asia/Taipei", 4.4, true)));

        var s = service("TW", store("RETAIL"), markets)
                .myStores(LoginUser.merchant("A1", "owner")).getFirst().stores().getFirst();

        assertEquals("TW", s.market());
        assertEquals("TWD", s.currency());
        assertEquals(2, s.currencyScale());
        assertEquals("Asia/Taipei", s.timeZone());
        assertEquals("RETAIL", s.businessTypeCode());
        assertEquals("RETAIL", s.industryCode());
        assertEquals("RETAIL", s.subIndustryCode());
    }

    @Test
    @DisplayName("AC5 取不到市场元数据时只给 market，不编造币种；无经营类型的旧店不编造行业")
    void unknownMarketMetadataAndLegacyStoreStayHonest() {
        var markets = mock(MarketPort.class);
        when(markets.find("XX")).thenReturn(Optional.empty());

        var s = service("XX", store(null), markets)
                .myStores(LoginUser.merchant("A1", "owner")).getFirst().stores().getFirst();

        assertEquals("XX", s.market());
        assertNull(s.currency());
        assertNull(s.currencyScale());
        assertNull(s.timeZone());
        assertNull(s.businessTypeCode());
        assertNull(s.industryCode());
        assertNull(s.subIndustryCode());
    }
}
