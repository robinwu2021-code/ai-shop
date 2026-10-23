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
}
