package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.dto.OperationContracts.Schedule;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderDecisionService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMaintenanceService;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Fulfillment;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.PaymentState;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Snapshot;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.State;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Totals;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

class ChannelOrderMaintenanceTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    static class Gateway implements ChannelOrderGateway {
        Document original;
        boolean unavailable;
        int reads;
        int sends;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.ORDER);
        }

        public Snapshot readOrder(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reads++;
            if (unavailable) throw new IllegalStateException("Mock original read unavailable");
            var now = Instant.now();
            return new Snapshot(
                    original,
                    now,
                    now.plusSeconds(25),
                    "MOCK_ORIGINAL:" + original.revisionRef(),
                    PricingSnapshotHash.of(original));
        }

        public boolean supportsDecision(Fulfillment fulfillment, Action action) {
            return true;
        }

        public DecisionResult decide(Decision decision) {
            sends++;
            throw new IllegalStateException("No SEND expected");
        }

        public DecisionResult queryDecision(Decision decision) {
            throw new IllegalStateException("No decision query expected");
        }
    }

    Document original(String revision, long sequence, String note) {
        var identity = new Identity("ORIGINAL", List.of());
        var line =
                new Line("LINE", identity, identity, "1.25", "KG", 100, 0, 0, 100, List.of(), note);
        return new Document(
                1,
                "REMOTE_ACCOUNT",
                "REMOTE_STORE",
                "EXTERNAL",
                revision,
                sequence,
                "a".repeat(64),
                "CNY",
                State.WAITING_ACCEPTANCE,
                PaymentState.PLATFORM_PAID,
                "MOCK_PAYMENT",
                Fulfillment.INSTANT_DELIVERY,
                Instant.parse("2026-10-28T00:00:00Z"),
                null,
                null,
                List.of(line),
                new Totals(0, 0, 0, 0, 100, 100));
    }

    void run(Gateway gateway, Consumer<CatalogSynchronizationTest.State> assertions) {
        run(gateway, UnaryOperator.identity(), assertions);
    }

    void run(
            Gateway gateway,
            UnaryOperator<ApplicationContextRunner> customization,
            Consumer<CatalogSynchronizationTest.State> assertions) {
        new CatalogSynchronizationTest()
                .runWith(
                        r ->
                                customization.apply(
                                        r.withBean(ChannelOrderGateway.class, () -> gateway)),
                        s -> {
                            s.sql()
                                    .execute(
                                            "UPDATE cim_authorization_revision SET"
                                                    + " granted_scopes='[\"CATALOG\",\"ORDER\"]'");
                            var initial = original("R1", 1, "Original");
                            var now = Instant.now();
                            var snapshot =
                                    new Snapshot(
                                            initial,
                                            now,
                                            now.plusSeconds(25),
                                            "MOCK_ORIGINAL:R1",
                                            PricingSnapshotHash.of(initial));
                            s.sql()
                                    .update(
                                            """
                                            INSERT INTO cim_order_link(channel_order_no,entity_no,store_no,connection_no,channel_store_key,
                                                external_order_ref,ordered_at,external_order_key,source_owner,connection_revision_no,
                                                active_revision_no,import_ref,import_status,acceptance_status,platform_status,
                                                fulfillment_status,financial_evidence_status)
                                            SELECT 'ORDER','E','S','CON',channel_store_key,'EXTERNAL','2026-10-28 00:00:00',?,
                                                'FOOD','CR','REV1','IMPORT','RECEIVED','UNCONFIRMED','WAITING_ACCEPTANCE','UNCONFIRMED','UNCONFIRMED'
                                            FROM cim_store_connection WHERE connection_no='CON'
                                            """,
                                            ChannelIdentity.hash("EXTERNAL"));
                            s.sql()
                                    .update(
                                            """
                                            INSERT INTO cim_order_revision(revision_no,entity_no,channel_order_no,external_revision,
                                                external_sequence,normalized_snapshot_ref,normalized_snapshot,original_receipt_ref,
                                                content_hash,catalog_hash,currency,captured_at)
                                            VALUES ('REV1','E','ORDER','R1',1,'DB:REV1',?,'MOCK_ORIGINAL:R1',?,?,'CNY',CURRENT_TIMESTAMP)
                                            """,
                                            json.writeValueAsString(snapshot),
                                            snapshot.documentHash(),
                                            "a".repeat(64));
                            assertions.accept(s);
                        });
    }

    @Test
    void refreshCommitsItsIntentBeforeHttpAndPreservesRevisionProvenance() {
        var gateway = new Gateway();
        gateway.original = original("R2", 2, "Changed");
        run(
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelOrderService.class);
                    var worker = s.context().getBean(ChannelOrderMaintenanceService.class);
                    var request = new ChannelOrderContracts.Refresh(0L);
                    var operation = api.refreshOrder("E", "S", "ORDER", "A", request, "refresh");
                    assertThat(api.refreshOrder("E", "S", "ORDER", "A", request, "refresh"))
                            .isEqualTo(operation);
                    assertThat(gateway.reads).isZero();
                    assertThat(worker.recoverDue(10)).isOne();
                    assertThat(gateway.reads).isOne();
                    assertThat(gateway.sends).isZero();
                    assertThat(api.listRevisions("E", "S", "ORDER", 1, 20).total()).isEqualTo(2);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT source_operation_no FROM"
                                                            + " cim_order_revision WHERE"
                                                            + " external_revision='R2'",
                                                    String.class))
                            .isEqualTo(operation.operationNo());
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_event",
                                                    Integer.class))
                            .isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT attempt_kind FROM cim_operation_attempt"
                                                            + " WHERE operation_no=?",
                                                    String.class,
                                                    operation.operationNo()))
                            .isEqualTo("QUERY");
                    assertThat(
                                    api.listLines("E", "S", "ORDER", "REV1", 1, 20)
                                            .records()
                                            .getFirst()
                                            .quantity())
                            .isEqualTo("1.25");
                    assertThatThrownBy(() -> api.listLines("E", "OTHER", "ORDER", "REV1", 1, 20))
                            .isInstanceOf(RuntimeException.class);
                    assertThatThrownBy(() -> api.listLines("E", "S", "ORDER", "MISSING", 1, 20))
                            .isInstanceOf(RuntimeException.class);
                });
    }

    @Test
    void anUnavailableReadRemainsQueryOnlyAndAConflictingRevisionNeverOverwritesTheOriginal() {
        var gateway = new Gateway();
        gateway.original = original("R1", 1, "Conflicting original body");
        run(
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelOrderService.class);
                    var worker = s.context().getBean(ChannelOrderMaintenanceService.class);
                    var operation =
                            api.refreshOrder(
                                    "E",
                                    "S",
                                    "ORDER",
                                    "A",
                                    new ChannelOrderContracts.Refresh(0L),
                                    "refresh");
                    gateway.unavailable = true;
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_operation WHERE"
                                                            + " operation_no=?",
                                                    String.class,
                                                    operation.operationNo()))
                            .isEqualTo("UNKNOWN");
                    gateway.unavailable = false;
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(worker.recoverDue(10)).isOne();
                    assertThat(api.getOrder("E", "S", "ORDER").order().importStatus())
                            .isEqualTo("CONFLICT");
                    assertThat(api.getOrder("E", "S", "ORDER").order().revisionNo())
                            .isEqualTo("REV1");
                    assertThat(api.listRevisions("E", "S", "ORDER", 1, 20).total()).isEqualTo(2);
                    assertThat(gateway.reads).isEqualTo(2);
                    assertThat(gateway.sends).isZero();
                });
    }

    @Test
    void importRecoveryWakesOnlyTheOriginalImportAndCannotOverrideALeaseOrConflict() {
        run(
                new Gateway(),
                s -> {
                    var api = s.context().getBean(ChannelOrderService.class);
                    s.sql()
                            .execute(
                                    "UPDATE cim_order_link SET"
                                        + " import_status='WAITING_OWNER',next_attempt_at=NULL");
                    var request =
                            new ChannelOrderContracts.Recover(
                                    0L, "Original owner is available again");
                    var operation = api.recoverImport("E", "S", "ORDER", "A", request, "recover");
                    assertThat(api.recoverImport("E", "S", "ORDER", "A", request, "recover"))
                            .isEqualTo(operation);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT import_ref FROM cim_order_link",
                                                    String.class))
                            .isEqualTo("IMPORT");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT import_status FROM cim_order_link",
                                                    String.class))
                            .isEqualTo("WAITING_OWNER");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_order_link WHERE"
                                                            + " next_attempt_at IS NOT NULL",
                                                    Integer.class))
                            .isOne();
                    s.sql().execute("UPDATE cim_order_link SET lease_until='2099-01-01 00:00:00'");
                    assertThatThrownBy(
                                    () ->
                                            api.recoverImport(
                                                    "E",
                                                    "S",
                                                    "ORDER",
                                                    "A",
                                                    new ChannelOrderContracts.Recover(1L, "Leased"),
                                                    "leased"))
                            .isInstanceOf(RuntimeException.class);
                    s.sql()
                            .execute(
                                    "UPDATE cim_order_link SET"
                                            + " lease_until=NULL,import_status='CONFLICT'");
                    assertThatThrownBy(
                                    () ->
                                            api.recoverImport(
                                                    "E",
                                                    "S",
                                                    "ORDER",
                                                    "A",
                                                    new ChannelOrderContracts.Recover(
                                                            1L, "Cannot clear conflict"),
                                                    "conflict"))
                            .isInstanceOf(RuntimeException.class);
                });
    }

    @Test
    void manualRecoveryCannotChangeTheFrozenSupplierRejectionReasonOrSendANewActionFromRefresh() {
        run(
                new Gateway(),
                s -> {
                    var api = s.context().getBean(ChannelOrderService.class);
                    var operations = s.context().getBean(ChannelOperationService.class);
                    var operation =
                            api.reject(
                                    "E",
                                    "S",
                                    "ORDER",
                                    "A",
                                    new ChannelOrderContracts.Reject(
                                            0L, "OUT_OF_STOCK", "Original supplier rejection"),
                                    "reject");
                    assertThatThrownBy(
                                    () ->
                                            operations.scheduleRecovery(
                                                    "E",
                                                    "S",
                                                    operation.operationNo(),
                                                    "A",
                                                    new Schedule(
                                                            operation.version(),
                                                            "Read only refresh"),
                                                    "refresh",
                                                    true))
                            .isInstanceOf(RuntimeException.class);
                    var hash =
                            s.sql()
                                    .queryForObject(
                                            "SELECT request_hash FROM cim_operation WHERE"
                                                    + " operation_no=?",
                                            String.class,
                                            operation.operationNo());
                    operations.scheduleRecovery(
                            "E",
                            "S",
                            operation.operationNo(),
                            "A",
                            new Schedule(operation.version(), "Recover original action"),
                            "recover",
                            false);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT reason FROM cim_operation WHERE"
                                                            + " operation_no=?",
                                                    String.class,
                                                    operation.operationNo()))
                            .isEqualTo("Original supplier rejection");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT request_hash FROM cim_operation WHERE"
                                                            + " operation_no=?",
                                                    String.class,
                                                    operation.operationNo()))
                            .isEqualTo(hash);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt",
                                                    Integer.class))
                            .isZero();
                });
    }

    @Test
    void expiredPreparationCannotSendAnAcceptanceEvenWhenItsReceiptIsPresent() {
        run(
                new Gateway(),
                s -> {
                    new ChannelFirstAcceptPreparationTest().prepare(s);
                    s.sql()
                            .execute(
                                    "UPDATE cim_order_link SET preparation_valid_until='2000-01-01"
                                        + " 00:00:00'");
                    assertThat(
                                    s.context()
                                            .getBean(ChannelOrderDecisionService.class)
                                            .recoverDue(10))
                            .isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT import_status FROM cim_order_link",
                                                    String.class))
                            .isEqualTo("WAITING_OWNER");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt"
                                                            + " WHERE attempt_kind='SEND'",
                                                    Integer.class))
                            .isZero();
                });
    }
}
