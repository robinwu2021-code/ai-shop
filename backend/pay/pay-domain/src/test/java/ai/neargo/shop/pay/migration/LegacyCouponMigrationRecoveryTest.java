package ai.neargo.shop.pay.migration;
import ai.neargo.shop.pay.migration.service.impl.LegacyCouponMigrationRecoveryServiceImpl;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationPlanMapper;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView;
import ai.neargo.shop.pay.migration.entity.EntMigrationPlan;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyCouponMigrationRecoveryTest {
    @Test void pendingAndPoisonPlansDoNotBlockLaterItemsOrCreateSourceCommitments() {
        var plans=mock(EntMigrationPlanMapper.class);var imports=mock(LegacyCouponMigrationImportService.class);
        var one=new EntMigrationPlan();one.setId(1L);one.setEntityNo("M1");one.setPlanNo("P1");
        var two=new EntMigrationPlan();two.setId(2L);two.setEntityNo("M1");two.setPlanNo("P2");
        var three=new EntMigrationPlan();three.setId(3L);three.setEntityNo("M1");three.setPlanNo("P3");
        when(plans.pendingImport("MAIN",0,3)).thenReturn(List.of(one,two,three));
        when(imports.importPlan(any(),eq("P1"),anyString(),anyString())).thenReturn(new ImportView("MI1","P1","M1","MD1","a".repeat(64),"IMPORTED",1,0,false,Instant.now()));
        when(imports.importPlan(any(),eq("P2"),anyString(),anyString())).thenThrow(BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        when(imports.importPlan(any(),eq("P3"),anyString(),anyString())).thenThrow(BizException.of(ErrorCode.CONFLICT));
        var result=new LegacyCouponMigrationRecoveryServiceImpl(plans,imports).recover("MAIN",0,3);
        assertThat(result.scanned()).isEqualTo(3);assertThat(result.completed()).isEqualTo(1);
        assertThat(result.deferred()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);assertThat(result.nextId()).isEqualTo(3);
        verify(imports).importPlan(argThat(scope->scope.platformActor() && "MIGRATION_RECOVERY".equals(scope.actorNo())),eq("P1"),anyString(),anyString());
        verify(imports).importPlan(any(),eq("P2"),anyString(),anyString());verify(imports).importPlan(any(),eq("P3"),anyString(),anyString());
        verifyNoMoreInteractions(imports);
    }
}
