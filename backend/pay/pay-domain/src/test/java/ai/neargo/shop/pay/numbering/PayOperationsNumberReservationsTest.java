package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.*;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationStageWriter;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PayOperationsNumberReservationsTest {
    @Test void actualStageCreationReservesBeforeTransactionAndReplayRetainsNumber() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ops-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var manager = new DataSourceTransactionManager(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE pay_document_sequence(seq_key VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
        var repository = new JdbcDocumentNumberRepository(DocumentOwner.PAY, manager);
        var generator = new SegmentDocumentNumberGenerator(repository, new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"), 2));
        DocumentNumberGenerator checkedGenerator = new DocumentNumberGenerator() {
            public ReservedDocumentNumber reserve(DocumentType type, java.time.Instant at) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return generator.reserve(type, at);
            }
            public ReservedDocumentNumber reserve(DocumentType type, java.time.LocalDate date) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return generator.reserve(type, date);
            }
            public List<ReservedDocumentNumber> reserve(DocumentType type, java.time.LocalDate date, int count) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return generator.reserve(type, date, count);
            }
        };
        var coordinator = new DocumentNumberCoordinator(repository, checkedGenerator, manager);
        var documents = new PayOperationsDocuments(coordinator);
        var mapper = mock(EntMigrationStageMapper.class);
        var row = new EntMigrationStage(); row.setTenantNo("MAIN"); row.setEntityNo("M1");
        row.setStageNo("MS-internal-stable"); row.setRequestKey("REQUEST"); row.setCommandHash("a".repeat(64));
        when(mapper.lockRequest("MAIN", "REQUEST")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return row;
        });
        var target = new EntitlementMigrationStageWriter(mapper);
        var field = EntitlementMigrationStageWriter.class.getDeclaredField("documents"); field.setAccessible(true); field.set(target, documents);
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvisor(new PayOperationsNumberReservations(coordinator));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var writer = (EntitlementMigrationStageWriter) factory.getProxy();
        assertThat(writer.claim(row)).isSameAs(row);
        String first = documents.find(DocumentType.ENTITLEMENT_MIGRATION_STAGE, "MAIN", row.getStageNo());
        assertThat(first).matches("MGS[0-9]{14,}");
        assertThat(writer.claim(row)).isSameAs(row);
        assertThat(documents.find(DocumentType.ENTITLEMENT_MIGRATION_STAGE, "MAIN", row.getStageNo())).isEqualTo(first);
        assertThat(documents.find(DocumentType.ENTITLEMENT_MIGRATION_STAGE, "MAIN", "historical")).isNull();
        assertThat(row.getStageNo()).isEqualTo("MS-internal-stable");
    }
    @Test void boundedBatchesAndMigrationBudgetCoverEveryPotentialCreatedFact() {
        assertThat(PayOperationsNumberReservations.budget("SettleBatchServiceImpl", "collectIntoBatches", new Object[]{null}))
                .containsEntry(DocumentType.SETTLEMENT_BATCH, 500);
        var args = new Object[8]; args[7] = Collections.nCopies(43, new Object());
        assertThat(PayOperationsNumberReservations.budget("LegacyCouponMigrationImportWriter", "importPlan", args))
                .containsEntry(DocumentType.ENTITLEMENT_MIGRATION_IMPORT, 1).containsEntry(DocumentType.ENTITLEMENT_USAGE, 43);
        assertThat(PayOperationsNumberReservations.budget("SettleServiceImpl", "generateForOrder", new Object[]{"O1", List.of("s1", "s2")}))
                .containsExactlyEntriesOf(Map.of(DocumentType.SETTLEMENT_BILL, 2));
        assertThat(PayOperationsNumberReservations.budget("SplitPreparationServiceImpl", "prepare", new Object[]{"ST1"}))
                .containsEntry(DocumentType.POINTS_VOUCHER, 1);
    }
    @Test void settlementResultCallbacksReserveFinalVoucherBeforeTheirFinancialTransaction() {
        assertThat(PayOperationsNumberReservations.owns("SettlementPointsFundingServiceImpl", "accept")).isTrue();
        assertThat(PayOperationsNumberReservations.owns("SplitResultServiceImpl", "acceptPoints")).isTrue();
        assertThat(PayOperationsNumberReservations.budget("SettlementPointsFundingServiceImpl", "accept", new Object[]{"S1", null}))
                .containsExactlyEntriesOf(Map.of(DocumentType.POINTS_VOUCHER, 1));
        assertThat(PayOperationsNumberReservations.budget("SplitResultServiceImpl", "acceptPoints", new Object[]{"R1", null}))
                .containsExactlyEntriesOf(Map.of(DocumentType.POINTS_VOUCHER, 1));
    }
    @Test void deadLetterCaptureAndBothRecoveryActionsHaveTheirOwnReservationBudget() {
        for (String method : List.of("captureDead", "retryEvent", "queryEvent")) {
            assertThat(PayOperationsNumberReservations.owns("EntitlementEventDeliveryServiceImpl", method)).isTrue();
            var type = method.equals("captureDead") ? DocumentType.ENTITLEMENT_EXCEPTION : DocumentType.ENTITLEMENT_EXCEPTION_ACTION;
            assertThat(PayOperationsNumberReservations.budget("EntitlementEventDeliveryServiceImpl", method, new Object[]{}))
                    .containsExactlyEntriesOf(Map.of(type, 1));
        }
    }
    @Test void projectionsBatchNumbersAndNeverReadOrGeneratePerRow() {
        var numbers = mock(DocumentNumberCoordinator.class);
        var documents = new PayOperationsDocuments(numbers);
        var one = new DocumentSource("MAIN", "PAY", "SETTLEMENT_BILL", "internal1");
        var two = new DocumentSource("MAIN", "PAY", "SETTLEMENT_BILL", "historical");
        when(numbers.findAll(DocumentType.SETTLEMENT_BILL, List.of(one,two))).thenReturn(Map.of(one,"STS20261020000001"));
        var result = documents.project(DocumentType.SETTLEMENT_BILL, List.of("internal1","historical"), no -> "MAIN", no -> no,
                no -> Optional.ofNullable(documents.find(DocumentType.SETTLEMENT_BILL,"MAIN",no)));
        assertThat(result).containsExactly(Optional.of("STS20261020000001"), Optional.empty());
        verify(numbers).findAll(DocumentType.SETTLEMENT_BILL, List.of(one,two)); verifyNoMoreInteractions(numbers);
    }
}
