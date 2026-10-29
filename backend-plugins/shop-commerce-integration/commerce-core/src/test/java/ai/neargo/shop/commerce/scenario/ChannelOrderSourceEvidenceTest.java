package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Original CIM persistence plus isolated platform contract: no native FOOD/core/inventory success
 * is fabricated.
 */
class ChannelOrderSourceEvidenceTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    ChannelOrderSourceEvidencePort.Query original(CatalogSynchronizationTest.State state) {
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
                        "UPDATE cim_order_link SET import_snapshot=? WHERE"
                            + " channel_order_no='ORDER'",
                        json.writeValueAsString(command));
        return new ChannelOrderSourceEvidencePort.Query(
                "E", "S", "IMPORT", Owner.FOOD, original.documentHash());
    }

    @Test
    void unchangedSupplierRevisionStillGetsAFreshDurableQueryAttemptBeforeNativeUse() {
        var gateway = new ChannelOrderMaintenanceTest.Gateway();
        var fixture = new ChannelOrderMaintenanceTest();
        gateway.original = fixture.original("R1", 1, "Original");
        fixture.run(
                gateway,
                state -> {
                    var query = original(state);
                    var port = state.context().getBean(ChannelOrderSourceEvidencePort.class);
                    var evidence = port.read(query);
                    assertThat(evidence.scope()).isEqualTo(query);
                    assertThat(evidence.original().original().documentHash())
                            .isEqualTo(query.originalDocumentHash());
                    assertThat(evidence.current().documentHash())
                            .isEqualTo(query.originalDocumentHash());
                    assertThat(evidence.routing().providerCode()).isEqualTo("TEST");
                    assertThat(evidence.operationNo()).isNotBlank();
                    assertThat(evidence.attemptRef()).isNotBlank();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_order_revision",
                                                    Integer.class))
                            .isEqualTo(1);
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation WHERE"
                                                        + " operation_type='CHANNEL_ORDER_REFRESH'"
                                                        + " AND status='SUCCEEDED'",
                                                    Integer.class))
                            .isEqualTo(1);
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT response_ref FROM cim_operation_attempt"
                                                        + " WHERE attempt_ref=?",
                                                    String.class,
                                                    evidence.attemptRef()))
                            .isEqualTo(evidence.current().originalReceiptRef());
                    assertThat(gateway.sends).isZero();
                    assertThat(gateway.reads).isEqualTo(1);
                });
    }

    @Test
    void anotherOwnerStoreOrOriginalHashCannotBorrowAnImportBeforeAnyPlatformRead() {
        var gateway = new ChannelOrderMaintenanceTest.Gateway();
        var fixture = new ChannelOrderMaintenanceTest();
        gateway.original = fixture.original("R1", 1, "Original");
        fixture.run(
                gateway,
                state -> {
                    var query = original(state);
                    var port = state.context().getBean(ChannelOrderSourceEvidencePort.class);
                    for (var wrong :
                            List.of(
                                    new ChannelOrderSourceEvidencePort.Query(
                                            "E",
                                            "OTHER",
                                            "IMPORT",
                                            Owner.FOOD,
                                            query.originalDocumentHash()),
                                    new ChannelOrderSourceEvidencePort.Query(
                                            "E",
                                            "S",
                                            "IMPORT",
                                            Owner.RETAIL,
                                            query.originalDocumentHash()),
                                    new ChannelOrderSourceEvidencePort.Query(
                                            "E", "S", "IMPORT", Owner.FOOD, "f".repeat(64)))) {
                        assertThatThrownBy(() -> port.read(wrong))
                                .isInstanceOf(RuntimeException.class);
                    }
                    assertThat(gateway.reads).isZero();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation",
                                                    Integer.class))
                            .isZero();
                });
    }

    @Test
    void platformReadFailureLeavesOnlyARecoverableQueryAndCannotAuthorizeNativeProduction() {
        var gateway = new ChannelOrderMaintenanceTest.Gateway();
        gateway.unavailable = true;
        new ChannelOrderMaintenanceTest()
                .run(
                        gateway,
                        state -> {
                            var query = original(state);
                            var port =
                                    state.context().getBean(ChannelOrderSourceEvidencePort.class);
                            assertThatThrownBy(() -> port.read(query))
                                    .isInstanceOf(RuntimeException.class);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM cim_operation",
                                                            String.class))
                                    .isEqualTo("UNKNOWN");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_operation"
                                                                + " WHERE next_recovery_at IS NOT"
                                                                + " NULL",
                                                            Integer.class))
                                    .isEqualTo(1);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                + " cim_operation_attempt WHERE"
                                                                + " outcome='SUCCEEDED'",
                                                            Integer.class))
                                    .isZero();
                            assertThat(gateway.sends).isZero();
                        });
    }
}
