package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueMigrationMaintenanceTest {
    final Actor actor=new Actor("MAIN","MCH:OWNER");
    final StoredValueMigrationCoordinator core=mock(StoredValueMigrationCoordinator.class);
    final StoredValueMigrationReadinessService readiness=mock(StoredValueMigrationReadinessService.class);
    final StoredValueMigrationAppService pay=mock(StoredValueMigrationAppService.class);
    final Preparation evidence=new Preparation(List.of(new Scope(new StoreAuthority("MAIN","MCH:OWNER","E1","S1","本店",7),"I1")),List.of(new Identity("P1","U1")));
    Proof proof(boolean sealed){
        var inventory=new Inventory("release1","b".repeat(64),sealed,List.of(new Node("pay1","PAY1","pay-svc","a".repeat(40),"boot-pay")));
        var cap=new Capability("nonce","pay-svc","boot-pay","a".repeat(40),1,true,true,"b".repeat(64),true,Instant.now());
        return new Proof(inventory,StoredValueMigrationReadiness.check(inventory,List.of(new NodeResult("pay1",cap,null)),"nonce",Instant.now()));
    }
    @Test void pauseAndMapUseFreshServerEvidenceAndExplicitOriginalPauseReceipt(){
        var p=proof(true);when(core.evidence(actor,List.of("S1"))).thenReturn(evidence);when(readiness.proof()).thenReturn(p);
        var paused=new MaintenanceResult("R1","M1","WRITE_FENCED",p.report().inventoryHash(),1,0);
        var mapped=new MaintenanceResult("R2","M1","METADATA_MAPPED",p.report().inventoryHash(),1,1);
        when(pay.pause(evidence,"M1","K1",p)).thenReturn(paused);when(pay.apply(evidence,"M1","R1","K2",p)).thenReturn(mapped);
        var service=new StoredValueMigrationMaintenanceService(core,readiness,pay,true);
        assertThat(service.pause(actor,List.of("S1"),"M1","K1")).isEqualTo(paused);
        assertThat(service.apply(actor,List.of("S1"),"M1","R1","K2")).isEqualTo(mapped);
        verify(core,times(2)).assertCurrent(actor,evidence);
    }
    @Test void closedOrUnreadyExecutionCannotReachPay(){
        var closed=new StoredValueMigrationMaintenanceService(core,readiness,pay,false);
        assertThatThrownBy(()->closed.pause(actor,List.of("S1"),"M1","K1")).isInstanceOf(BizException.class);verifyNoInteractions(core,readiness,pay);
        when(core.evidence(actor,List.of("S1"))).thenReturn(evidence);when(readiness.proof()).thenReturn(proof(false));
        assertThatThrownBy(()->new StoredValueMigrationMaintenanceService(core,readiness,pay,true).pause(actor,List.of("S1"),"M1","K1")).isInstanceOf(BizException.class);
        verifyNoInteractions(pay);
    }
    @Test void revokedOwnerAfterReadinessCannotPauseOrApply(){
        when(core.evidence(actor,List.of("S1"))).thenReturn(evidence);when(readiness.proof()).thenReturn(proof(true));
        doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(core).assertCurrent(actor,evidence);
        assertThatThrownBy(()->new StoredValueMigrationMaintenanceService(core,readiness,pay,true).pause(actor,List.of("S1"),"M1","K1")).isInstanceOf(BizException.class);
        verifyNoInteractions(pay);
    }
}
