package ai.neargo.shop.pay.migration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.entity.EntOwnerRegistry;
import ai.neargo.shop.pay.migration.service.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
class LegacyCouponHistoricalUsageWriterTest {
    @Test void historicalFactCreatesNoInventedBalanceOrNativeIntentAndUnknownFieldsRemainNull() {
        var usages=mock(EntUsageMapper.class);var mappings=mock(EntMigrationUsageMapper.class);var owners=mock(EntOwnerRegistryMapper.class);
        when(usages.insert(any(EntUsage.class))).thenReturn(1);when(mappings.insert(any(ai.neargo.shop.pay.migration.entity.EntMigrationUsage.class))).thenReturn(1);
        var original=new EntOwnerRegistry();original.setId(1L);original.setVersion(0L);original.setOwnerSystem("CORE_LEGACY");original.setMigrationBatchNo("B1");
        when(owners.lock("MAIN","PMT","USAGE","AP1")).thenReturn(original);when(owners.cutover(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        var at=Instant.parse("2026-10-01T00:00:00Z");
        var fact=new LegacyCouponHistoricalUsageProjector.Fact("EU1","AP1","OLD1",null,"U1","M1",null,null,"O1",0,at,at.plusSeconds(1),"a".repeat(64));
        new LegacyCouponHistoricalUsageWriter(usages,mappings,owners).write(new EntitlementContext("MAIN","MIGRATION","I1","b".repeat(64)),
                "MI1","B1","c".repeat(64),List.of(fact));
        var saved=ArgumentCaptor.forClass(EntUsage.class);verify(usages).insert(saved.capture());
        assertThat(saved.getValue().getProtocol()).isEqualTo("MIGRATED");assertThat(saved.getValue().getOperationNo()).isEqualTo("AP1");
        assertThat(saved.getValue().getStatus()).isEqualTo("REVERSED");assertThat(saved.getValue().getAmountMinor()).isZero();
        assertThat(saved.getValue().getLegacyInstanceNo()).isNull();assertThat(saved.getValue().getOperatorNo()).isNull();
        assertThat(saved.getValue().getStoreNo()).isNull();assertThat(saved.getValue().getStoreName()).isNull();
        assertThat(saved.getValue().getItemsSnapshot()).isEqualTo("[]");
    }
}
