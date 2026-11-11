package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementQueryServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrandCouponQueryProjectionTest {
    @Test void walletAndDetailDescribeBrandScopeWithoutExposingOpaqueSourceAsAStore() {
        var json = new ObjectMapper();
        var instances = mock(EntInstanceMapper.class);
        var usages = mock(EntUsageMapper.class);
        var service = new EntitlementQueryServiceImpl(instances, mock(EntPeriodBucketMapper.class), usages,
                mock(EntUseAllocationMapper.class), mock(EntLedgerMapper.class), mock(EntitlementCursorCodec.class),
                json, mock(StoredValueCursorCodec.class));
        var context = new EntitlementContext("MAIN", "U1", "READ", "a".repeat(64));
        var definition = new EntitlementDefinition("brand coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH",
                100L, null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("BRAND_STORES", List.of("OPAQUE-CORE-SOURCE")),
                List.of("TARGETED"));
        var asset = new EntInstance();
        asset.setId(1L);
        asset.setTenantNo("MAIN");
        asset.setInstanceNo("I1");
        asset.setEntityNo("ISSUER");
        asset.setUserNo("U1");
        asset.setKind("ORDER_COUPON");
        asset.setStatus("ACTIVE");
        asset.setRemainingUnits(1);
        asset.setReservedUnits(0);
        asset.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        asset.setEffectiveUntil(LocalDateTime.now().plusDays(1));
        asset.setRuleSnapshot(json.writeValueAsString(definition));
        when(instances.selectList(any())).thenReturn(List.of(asset));
        when(instances.selectOne(any())).thenReturn(asset);
        when(usages.recent("MAIN", "U1", "ISSUER", "I1", null)).thenReturn(List.of());

        var wallet = service.mine(context, new AssetQuery("U1", "ISSUER", AssetKind.ORDER_COUPON, null, null, 20));
        assertThat(wallet.records()).hasSize(1);
        assertThat(wallet.records().getFirst().scopeSummary()).isEqualTo("品牌参与门店");
        var detail = service.detail(context, new DetailQuery("U1", "ISSUER", "I1", null));
        assertThat(detail.entitlement()).isEqualTo(wallet.records().getFirst());
        assertThat(json.writeValueAsString(wallet)).doesNotContain("OPAQUE-CORE-SOURCE");
        assertThat(json.writeValueAsString(detail)).doesNotContain("OPAQUE-CORE-SOURCE");
    }
}
