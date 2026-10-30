package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts.Accept;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderDecisionService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMaintenanceService;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Isolated first-SEND contract tests; these doubles do not prove native FOOD/core/inventory
 * integration.
 */
class ChannelFirstAcceptPreparationTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    static class OriginalOwner implements ChannelOrderIngestPort {
        Status status = Status.PREPARED;
        int reads;

        public Owner owner() {
            return Owner.FOOD;
        }

        public Prepared getImport(Reference reference) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reads++;
            return new Prepared(
                    reference,
                    status,
                    status == Status.PREPARED ? "ACTUAL_NATIVE_PREPARATION" : null,
                    status == Status.PREPARED ? Instant.now().plusSeconds(900) : null);
        }

        public Prepared prepareImport(Command command) {
            throw new UnsupportedOperationException();
        }

        public Accepted confirmAcceptance(Confirmation confirmation) {
            throw new UnsupportedOperationException();
        }

        public Cancelled cancelPreparedImport(Reference reference, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    static class Gateway extends ChannelOrderMaintenanceTest.Gateway {
        boolean loseSend;
        int queries;

        @Override
        public DecisionResult decide(Decision decision) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends++;
            if (loseSend) throw new IllegalStateException("Original action response lost");
            return result(decision);
        }

        @Override
        public DecisionResult queryDecision(Decision decision) {
            queries++;
            return result(decision);
        }

        DecisionResult result(Decision decision) {
            return new DecisionResult(
                    decision.operationNo(),
                    decision.scope().externalOrderRef(),
                    decision.revisionRef(),
                    decision.action(),
                    DecisionStatus.CONFIRMED,
                    "ACTUAL_PLATFORM_ACCEPTANCE");
        }
    }

    void prepare(CatalogSynchronizationTest.State state) {
        var original =
                json.readValue(
                        state.sql()
                                .queryForObject(
                                        "SELECT normalized_snapshot FROM cim_order_revision WHERE"
                                            + " revision_no='REV1'",
                                        String.class),
                        ChannelOrderGateway.Snapshot.class);
        var command =
                new ChannelOrderIngestPort.Command(
                        "IMPORT", "E", "S", "CON", "CR", "REV1", original, List.of());
        state.sql()
                .update(
                        "UPDATE cim_order_link SET"
                            + " import_snapshot=?,import_status='PREPARED',preparation_receipt_ref='CACHED_PREPARATION',preparation_valid_until=?"
                            + " WHERE channel_order_no='ORDER'",
                        json.writeValueAsString(command),
                        LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        state.context()
                .getBean(ChannelOrderService.class)
                .accept("E", "S", "ORDER", "ACTOR", new Accept(0L), "ACCEPT");
    }

    @Test
    void unexpiredCachedReceiptCannotSendWhenTheRealOwnerIsNoLongerReady() {
        var owner = new OriginalOwner();
        owner.status = ChannelOrderIngestPort.Status.WAITING_OWNER;
        var gateway = new Gateway();
        new ChannelOrderMaintenanceTest()
                .run(
                        gateway,
                        runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                        state -> {
                            prepare(state);
                            assertThat(
                                            state.context()
                                                    .getBean(ChannelOrderDecisionService.class)
                                                    .recoverDue(10))
                                    .isZero();
                            assertThat(owner.reads).isEqualTo(1);
                            assertThat(gateway.sends).isZero();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                + " cim_operation_attempt",
                                                            Integer.class))
                                    .isZero();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT import_status FROM"
                                                                + " cim_order_link",
                                                            String.class))
                                    .isEqualTo("WAITING_OWNER");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM cim_operation",
                                                            String.class))
                                    .isEqualTo("QUEUED");
                        });
    }

    @Test
    void firstSendPersistsFreshOriginalReadinessBeforeDispatch() {
        var owner = new OriginalOwner();
        var gateway = new Gateway();
        new ChannelOrderMaintenanceTest()
                .run(
                        gateway,
                        runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                        state -> {
                            prepare(state);
                            assertThat(
                                            state.context()
                                                    .getBean(ChannelOrderDecisionService.class)
                                                    .recoverDue(10))
                                    .isEqualTo(1);
                            assertThat(owner.reads).isEqualTo(1);
                            assertThat(gateway.sends).isEqualTo(1);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT preparation_receipt_ref FROM"
                                                                + " cim_order_link",
                                                            String.class))
                                    .isEqualTo("ACTUAL_NATIVE_PREPARATION");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT attempt_kind FROM"
                                                                + " cim_operation_attempt",
                                                            String.class))
                                    .isEqualTo("SEND");
                        });
    }

    @Test
    void anUncertainSendOnlyQueriesEvenWhenTheOriginalOwnerLaterBecomesUnavailable() {
        var owner = new OriginalOwner();
        var gateway = new Gateway();
        gateway.loseSend = true;
        new ChannelOrderMaintenanceTest()
                .run(
                        gateway,
                        runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                        state -> {
                            prepare(state);
                            var worker = state.context().getBean(ChannelOrderDecisionService.class);
                            worker.recoverDue(10);
                            owner.status = ChannelOrderIngestPort.Status.WAITING_OWNER;
                            state.sql()
                                    .execute(
                                            "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                                + " 00:00:00',lease_until=NULL");
                            assertThat(worker.recoverDue(10)).isEqualTo(1);
                            assertThat(owner.reads).isEqualTo(1);
                            assertThat(gateway.sends).isEqualTo(1);
                            assertThat(gateway.queries).isEqualTo(1);
                            assertThat(
                                            state.sql()
                                                    .queryForList(
                                                            "SELECT attempt_kind FROM"
                                                                + " cim_operation_attempt ORDER BY"
                                                                + " attempt_no",
                                                            String.class))
                                    .containsExactly("SEND", "QUERY");
                        });
    }

    @Test
    void paymentOnlyRevisionCanRetargetANeverDispatchedIntentWithoutChangingTheOriginalImport() {
        var owner = new OriginalOwner();
        var gateway = new Gateway();
        var fixture = new ChannelOrderMaintenanceTest();
        fixture.run(
                gateway,
                runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                state -> {
                    prepare(state);
                    var original =
                            state.sql()
                                    .queryForObject(
                                            "SELECT import_snapshot FROM cim_order_link",
                                            String.class);
                    gateway.original = fixture.original("R2", 2, "Original");
                    state.context()
                            .getBean(ChannelOrderMaintenanceService.class)
                            .readOriginal("E", "S", "ORDER");
                    assertThat(
                                    state.context()
                                            .getBean(ChannelOrderDecisionService.class)
                                            .recoverDue(10))
                            .isEqualTo(1);
                    assertThat(gateway.sends).isEqualTo(1);
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT import_snapshot FROM cim_order_link",
                                                    String.class))
                            .isEqualTo(original);
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation WHERE"
                                                        + " operation_type='CHANNEL_ORDER_ACCEPT'"
                                                        + " AND status='SUCCEEDED'",
                                                    Integer.class))
                            .isEqualTo(1);
                });
    }

    @Test
    void anActualAcceptedOrderReadAvoidsASecondSendButDoesNotFabricateAnOutboundSuccess() {
        var owner = new OriginalOwner();
        var gateway = new Gateway();
        var fixture = new ChannelOrderMaintenanceTest();
        fixture.run(
                gateway,
                runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                state -> {
                    prepare(state);
                    var d = fixture.original("R2", 2, "Original");
                    gateway.original =
                            new ChannelOrderGateway.Document(
                                    d.schemaVersion(),
                                    d.externalAccountRef(),
                                    d.externalStoreRef(),
                                    d.externalOrderRef(),
                                    d.revisionRef(),
                                    d.revisionSequence(),
                                    d.catalogHash(),
                                    d.currency(),
                                    ChannelOrderGateway.State.ACCEPTED,
                                    d.paymentState(),
                                    d.originalPaymentRef(),
                                    d.fulfillment(),
                                    d.orderedAt(),
                                    d.requestedAt(),
                                    d.customer(),
                                    d.lines(),
                                    d.totals());
                    state.context()
                            .getBean(ChannelOrderMaintenanceService.class)
                            .readOriginal("E", "S", "ORDER");
                    state.context().getBean(ChannelOrderDecisionService.class).recoverDue(10);
                    assertThat(gateway.sends).isZero();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_operation WHERE"
                                                        + " operation_type='CHANNEL_ORDER_ACCEPT'",
                                                    String.class))
                            .isEqualTo("SUPERSEDED");
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT import_status FROM cim_order_link",
                                                    String.class))
                            .isEqualTo("WAITING_CONFIRMATION");
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT platform_acceptance_receipt_ref FROM"
                                                        + " cim_order_link",
                                                    String.class))
                            .isEqualTo("MOCK_ORIGINAL:R2");
                });
    }
}
