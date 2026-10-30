package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderImportService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMaintenanceService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Snapshot;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.State;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * Isolated original-owner contract double: these cases do not claim native restaurant/core
 * integration.
 */
class ChannelPreparedWithdrawalTest {
    static final class OriginalOwner implements ChannelOrderIngestPort {
        Status state = Status.PREPARED;
        boolean missing;
        boolean unavailable;
        int reads;
        int cancels;

        public Owner owner() {
            return Owner.FOOD;
        }

        public Prepared prepareImport(Command command) {
            throw new AssertionError("Cancellation cannot prepare");
        }

        public Prepared getImport(Reference reference) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reads++;
            if (missing) throw BizException.of(ErrorCode.NOT_FOUND);
            if (unavailable) throw new IllegalStateException("Original owner unavailable");
            return new Prepared(
                    reference,
                    state,
                    state == Status.PREPARED ? "ORIGINAL_HOLD" : null,
                    state == Status.PREPARED ? Instant.now().plusSeconds(600) : null);
        }

        public Accepted confirmAcceptance(Confirmation confirmation) {
            throw new AssertionError("No confirm on cancel");
        }

        public Cancelled cancelPreparedImport(Reference reference, String reason) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(reason).isEqualTo("PLATFORM_CANCELLED");
            cancels++;
            return new Cancelled(reference, "REAL_ORIGINAL_WITHDRAWAL");
        }
    }

    void run(OriginalOwner owner, String expectedStatus, int expectedCancels) {
        var fixture = new ChannelOrderMaintenanceTest();
        var gateway = new ChannelOrderMaintenanceTest.Gateway();
        var old = fixture.original("R1", 1, "Original");
        gateway.original =
                new Document(
                        old.schemaVersion(),
                        old.externalAccountRef(),
                        old.externalStoreRef(),
                        old.externalOrderRef(),
                        "R2",
                        2L,
                        old.catalogHash(),
                        old.currency(),
                        State.CANCELLED,
                        old.paymentState(),
                        old.originalPaymentRef(),
                        old.fulfillment(),
                        old.orderedAt(),
                        old.requestedAt(),
                        old.customer(),
                        old.lines(),
                        old.totals());
        fixture.run(
                gateway,
                runner -> runner.withBean(ChannelOrderIngestPort.class, () -> owner),
                state -> {
                    var initial =
                            fixture.json.readValue(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT normalized_snapshot FROM"
                                                            + " cim_order_revision WHERE"
                                                            + " revision_no='REV1'",
                                                    String.class),
                                    Snapshot.class);
                    var command =
                            new ChannelOrderIngestPort.Command(
                                    "IMPORT", "E", "S", "CON", "CR", "REV1", initial, List.of());
                    state.sql()
                            .update(
                                    "UPDATE cim_order_link SET"
                                            + " import_snapshot=?,import_status='PREPARED'",
                                    fixture.json.writeValueAsString(command));
                    var api = state.context().getBean(ChannelOrderService.class);
                    api.refreshOrder(
                            "E",
                            "S",
                            "ORDER",
                            "A",
                            new ChannelOrderContracts.Refresh(0L),
                            "cancel-read");
                    state.context().getBean(ChannelOrderMaintenanceService.class).recoverDue(10);
                    assertThat(api.getOrder("E", "S", "ORDER").order().importStatus())
                            .isEqualTo("WAITING_CANCELLATION");
                    state.context().getBean(ChannelOrderImportService.class).recoverDue(10);
                    assertThat(api.getOrder("E", "S", "ORDER").order().importStatus())
                            .isEqualTo(expectedStatus);
                    assertThat(owner.reads).isOne();
                    assertThat(owner.cancels).isEqualTo(expectedCancels);
                    assertThat(gateway.sends).isZero();
                });
    }

    @Test
    void preparedOriginalWithdrawsOnlyAfterReadingItsOwner() {
        run(new OriginalOwner(), "CANCELLED", 1);
    }

    @Test
    void realCoreCommitGoesToAfterSaleWithoutPreparedWithdrawal() {
        var owner = new OriginalOwner();
        owner.state = ChannelOrderIngestPort.Status.COMMITTED;
        run(owner, "WAITING_AFTER_SALE", 0);
    }

    @Test
    void nativeAcceptedOriginalIsNeverPreparedCancelled() {
        var owner = new OriginalOwner();
        owner.state = ChannelOrderIngestPort.Status.ACCEPTED;
        run(owner, "WAITING_AFTER_SALE", 0);
    }

    @Test
    void absenceStillRequiresAnOriginalCancellationTombstone() {
        var owner = new OriginalOwner();
        owner.missing = true;
        run(owner, "CANCELLED", 1);
    }

    @Test
    void unavailableOwnerCannotAuthorizeWithdrawalOrMarkTerminal() {
        var owner = new OriginalOwner();
        owner.unavailable = true;
        run(owner, "WAITING_CANCELLATION", 0);
    }
}
