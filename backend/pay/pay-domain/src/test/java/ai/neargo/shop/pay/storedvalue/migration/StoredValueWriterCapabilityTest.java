package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueWriterCapabilityTest {
    IssuerRepository db=mock(IssuerRepository.class);
    StoredValueWriterCapabilityService service(String sha,boolean enabled) {
        var props=new Properties();if(sha!=null)props.put("gitSha",sha);
        var beans=new StaticListableBeanFactory(Map.of("build",new BuildProperties(props)));
        return new StoredValueWriterCapabilityService(db,beans.getBeanProvider(BuildProperties.class),"pay-svc",enabled,true);
    }
    @Test void nodeReportsItsActualBuildSwitchesAndDatabaseIdentityWithoutConnectionDetails(){
        when(db.one(StoredValueWriterCapabilityService.LEDGER_ID_SQL)).thenReturn(Map.of("ledger_fingerprint","b".repeat(64)));
        when(db.one(StoredValueWriterCapabilityService.SCHEMA_SQL)).thenReturn(Map.of("applied",3L));
        var service=service("a".repeat(40),true);var first=service.capability(new Query("nonce1"));
        assertThat(first.buildSha()).isEqualTo("a".repeat(40));assertThat(first.ledgerFingerprint()).isEqualTo("b".repeat(64));
        assertThat(first.schemaReady()).isTrue();assertThat(first.protocol()).isEqualTo(2);assertThat(first.writerGuardEnabled()).isTrue();assertThat(first.challenge()).isEqualTo("nonce1");
        assertThat(service.capability(new Query("nonce2")).processNo()).isEqualTo(first.processNo());
        assertThat(service("a".repeat(40),false).capability(new Query("nonce1")).writerGuardEnabled()).isFalse();
    }
    @Test void unknownBuildOrDatabaseFailureCannotProduceReadyEvidence(){
        var unknown=service(null,true).capability(new Query("nonce"));
        assertThat(unknown.buildSha()).isEqualTo("unknown");assertThat(unknown.schemaReady()).isFalse();
        when(db.one(StoredValueWriterCapabilityService.LEDGER_ID_SQL)).thenThrow(new DataAccessResourceFailureException("private connection detail"));
        var unavailable=service("a".repeat(40),true).capability(new Query("nonce"));
        assertThat(unavailable.schemaReady()).isFalse();assertThat(unavailable.ledgerFingerprint()).isNull();
        assertThat(unavailable.toString()).doesNotContain("private connection detail");
    }

}
