package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.svc.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=PayApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="shop.services.internal-token=migration-test-token")
@ActiveProfiles("svctest")
class StoredValueMigrationInternalApiTest {
    @LocalServerPort int port;
    @MockitoBean StoredValueMigrationManifestService migration;
    @MockitoBean ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationExecutionService execution;
    @MockitoBean ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService writer;
    PayInternalApi api(String token) {
        var locator=new ConfigServiceLocator();locator.getTargets().put(ServiceName.PAY,"http://127.0.0.1:"+port);
        return new InternalHttp(locator,token).client(ServiceName.PAY,PayInternalApi.class,Duration.ofSeconds(5));
    }
    @Test void typedProbeAndManifestCrossTheActualInternalHttpBoundaryAndRejectWrongToken() {
        var scope=new StoreAuthority("MAIN","OWNER","E1","S1","测试店",7);
        var scopes=List.of(scope);var probe=new Probe(List.of(new Scope(scope,"I1")),List.of(new Subject("A1","S1","E1","P1",null)));
        var preparation=new Preparation(probe.scopes(),List.of(new Identity("P1",null)));
        var manifest=new Manifest("SVM1","fingerprint","authority",1);
        when(migration.probe(scopes)).thenReturn(probe);when(migration.prepareTrusted(preparation,"K1")).thenReturn(manifest);
        var request=new PayStoredValueMigrationRequests.Probe(scopes);
        assertThatThrownBy(()->api("wrong").svMigrationProbe(request)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(migration);
        assertThat(api("migration-test-token").svMigrationProbe(request)).isEqualTo(probe);
        assertThat(api("migration-test-token").svMigrationPrepare(new PayStoredValueMigrationRequests.Prepare(preparation,"K1"))).isEqualTo(manifest);
        verify(migration).probe(scopes);verify(migration).prepareTrusted(preparation,"K1");
    }
    @Test void capabilityIsTokenProtectedAndReturnsTheFreshProcessObservation() {
        var query=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query("nonce-current");
        var capability=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Capability("nonce-current","pay-svc","boot-pay","a".repeat(40),1,true,true,"b".repeat(64),true,java.time.Instant.now());
        when(writer.capability(query)).thenReturn(capability);
        assertThatThrownBy(()->api("wrong").svMigrationCapability(query)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(writer);
        assertThat(api("migration-test-token").svMigrationCapability(query)).isEqualTo(capability);
    }

    @Test void maintenanceCarriesProofAndLinkedPauseReceiptOverAuthenticatedHttp() {
        var scope=new StoreAuthority("MAIN","OWNER","E1","S1","本店",7);
        var input=new Preparation(List.of(new Scope(scope,"I1")),List.of(new Identity("P1",null)));
        var now=java.time.Instant.now();
        var inventory=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Inventory("R1","b".repeat(64),true,List.of(new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Node("pay1","PAY1","pay-svc","a".repeat(40),"boot-pay")));
        var cap=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Capability("nonce","pay-svc","boot-pay","a".repeat(40),1,true,true,"b".repeat(64),true,now);
        var report=ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.check(inventory,List.of(new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.NodeResult("pay1",cap,null)),"nonce",now);
        var proof=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof(inventory,report);
        var paused=new MaintenanceResult("R1","M1","WRITE_FENCED",report.inventoryHash(),1,0);
        var mapped=new MaintenanceResult("R2","M1","METADATA_MAPPED",report.inventoryHash(),1,1);
        when(execution.pause(input,"M1","K1",proof)).thenReturn(paused);when(execution.apply(input,"M1","R1","K2",proof)).thenReturn(mapped);
        var request=new PayStoredValueMigrationRequests.Pause(input,"M1","K1",proof);
        assertThatThrownBy(()->api("wrong").svMigrationPause(request)).isInstanceOf(RuntimeException.class);verifyNoInteractions(execution);
        assertThat(api("migration-test-token").svMigrationPause(request)).isEqualTo(paused);
        assertThat(api("migration-test-token").svMigrationApply(new PayStoredValueMigrationRequests.Apply(input,"M1","R1","K2",proof))).isEqualTo(mapped);
    }

}
