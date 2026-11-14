package ai.neargo.shop.numbering.store;

import ai.neargo.shop.numbering.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;

class DocumentNumberCoordinatorTest {
    @Test void reservesOutsideTransactionAndNestedCallsConsumeOnlyOuterBudget() {
        var f=new Fixture();
        var one=new DocumentSource("MAIN","PAY","GRANT","G1");
        var two=new DocumentSource("MAIN","PAY","GRANT","G2");
        f.coordinator.withReservation(Map.of(DocumentType.ENTITLEMENT_GRANT,2),()-> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return f.coordinator.transaction(Map.of(DocumentType.ENTITLEMENT_GRANT,1),()-> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                String a=f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,one);
                assertThat(f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,one)).isEqualTo(a);
                String b=f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,two);
                assertThat(b).isNotEqualTo(a);
                assertThatThrownBy(()->f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,
                    new DocumentSource("MAIN","PAY","GRANT","G3"))).isInstanceOf(IllegalStateException.class);
                return a;
            });
        });
        assertThat(f.coordinator.findAll(DocumentType.ENTITLEMENT_GRANT,List.of(one,two))).hasSize(2);
        assertThatThrownBy(()->f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,one)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rollbackRemovesMappingAndClearsContextButDoesNotReissueNumbers() {
        var f=new Fixture();var source=new DocumentSource("MAIN","PAY","VOUCHER","V1");
        var first=new String[1];
        assertThatThrownBy(()->f.coordinator.transaction(Map.of(DocumentType.SV_VOUCHER,1),()-> {
            first[0]=f.coordinator.bind(DocumentType.SV_VOUCHER,source);throw new IllegalArgumentException("business failure");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.coordinator.find(DocumentType.SV_VOUCHER,source)).isEmpty();
        String second=f.coordinator.transaction(Map.of(DocumentType.SV_VOUCHER,1),()->f.coordinator.bind(DocumentType.SV_VOUCHER,source));
        assertThat(second).isNotEqualTo(first[0]);
        new TransactionTemplate(f.manager).executeWithoutResult(tx->assertThatThrownBy(()->
            f.coordinator.withReservation(Map.of(DocumentType.SV_VOUCHER,1),()->null)).isInstanceOf(IllegalStateException.class));
    }
    @Test void adoptedDomainKeysUseOnlyPreviouslyReservedNumbers() {
        var f=new Fixture();
        String number=f.coordinator.transaction(Map.of(DocumentType.SV_RECHARGE,1),()-> {
            var reserved=f.coordinator.takeReserved(DocumentType.SV_RECHARGE);
            return f.coordinator.bindReserved(new DocumentSource("MAIN","PAY","SV_RECHARGE",reserved.value()),reserved);
        });
        assertThat(number).startsWith("PR");
        assertThat(f.coordinator.find(DocumentType.SV_RECHARGE,new DocumentSource("MAIN","PAY","SV_RECHARGE",number))).contains(number);
    }
    @Test void batchedLookupSeparatesTenantSourceNamespaceAndType() {
        var f=new Fixture();
        var a=new DocumentSource("MAIN","LEGACY","TEMPLATE","same");
        var b=new DocumentSource("MAIN","NATIVE","TEMPLATE","same");
        var c=new DocumentSource("OTHER","NATIVE","TEMPLATE","same");
        f.coordinator.transaction(Map.of(DocumentType.ENTITLEMENT_GRANT,3),()-> {
            for(var source:List.of(a,b,c))f.coordinator.bind(DocumentType.ENTITLEMENT_GRANT,source);return null;
        });
        var found=f.coordinator.findAll(DocumentType.ENTITLEMENT_GRANT,List.of(a,b));
        assertThat(found).hasSize(2).doesNotContainKey(c);
        assertThat(found.get(a)).isNotEqualTo(found.get(b));
        assertThat(f.coordinator.findAll(DocumentType.SV_VOUCHER,List.of(a,b))).isEmpty();
    }
    static class Fixture {
        final DataSourceTransactionManager manager;
        final DocumentNumberCoordinator coordinator;
        Fixture() {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:coord-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            manager=new DataSourceTransactionManager(ds);var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE pay_document_sequence(seq_key VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
            var repo=new JdbcDocumentNumberRepository(DocumentOwner.PAY,manager);
            coordinator=new DocumentNumberCoordinator(repo,new SegmentDocumentNumberGenerator(repo,new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),3)),manager);
        }
    }
}
