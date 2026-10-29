package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.FulfillmentContracts;
import ai.neargo.shop.commerce.policy.ChannelFulfillmentPolicy;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.commerce.service.impl.ChannelFulfillmentServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

class ChannelFulfillmentPublicationTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    static class Source implements ChannelFulfillmentSourcePort {
        boolean unavailable;
        String quantity = "1.25";
        String unit = "KG";
        Stage stage = Stage.READY;
        final Instant occurredAt = Instant.now();
        int calls;

        public Owner owner() {
            return Owner.FOOD;
        }

        public Facts capture(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (unavailable) throw new IllegalStateException("Original owner unavailable");
            var data =
                    new Data(
                            query,
                            stage,
                            null,
                            null,
                            null,
                            List.of(
                                    new Line(
                                            "NATIVE_LINE",
                                            "MAIN_COMPONENT",
                                            "OPAQUE_ITEM",
                                            quantity,
                                            unit)),
                            occurredAt,
                            "ORIGINAL_COMMITTED_FULFILLMENT");
            var now = Instant.now();
            return new Facts(data, now, now.plusSeconds(25), "ORIGINAL_VALIDATION");
        }
    }

    static class Gateway implements ChannelFulfillmentGateway {
        boolean timeout = true;
        int sends;
        int queries;
        Runnable during;
        FulfillmentPublication sent;
        FulfillmentPublication queried;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.FULFILLMENT);
        }

        public boolean supportsFulfillment(
                ChannelOrderGateway.Fulfillment fulfillment, Stage stage) {
            return ChannelFulfillmentPolicy.supports(fulfillment, stage);
        }

        public PublicationResult publishFulfillment(FulfillmentPublication p) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends++;
            sent = p;
            if (during != null) {
                var hook = during;
                during = null;
                hook.run();
            }
            return result(p, timeout ? PublicationStatus.UNKNOWN : PublicationStatus.CONFIRMED);
        }

        public PublicationResult queryFulfillment(FulfillmentPublication p) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            queries++;
            queried = p;
            return result(p, PublicationStatus.CONFIRMED);
        }

        PublicationResult result(FulfillmentPublication p, PublicationStatus status) {
            return new PublicationResult(
                    p.operationNo(),
                    p.scope().externalOrderRef(),
                    PricingSnapshotHash.of(p),
                    status,
                    status == PublicationStatus.UNKNOWN
                            ? null
                            : "MOCK_PLATFORM_FULFILLMENT_RECEIPT");
        }
    }

    FulfillmentContracts.Publish request() {
        return new FulfillmentContracts.Publish("ORDER", Owner.FOOD, "ORIGINAL_FULFILLMENT", "R1");
    }

    void run(
            Source source, Gateway gateway, Consumer<CatalogSynchronizationTest.State> assertions) {
        run(source, gateway, "KG", "KG", "1.25", assertions);
    }

    void run(
            Source source,
            Gateway gateway,
            String supplierUnit,
            String allocationUnit,
            String quantity,
            Consumer<CatalogSynchronizationTest.State> assertions) {
        var original = new ChannelOrderMaintenanceTest();
        original.run(
                new ChannelOrderMaintenanceTest.Gateway(),
                r -> {
                    var configured = r.withBean(ChannelFulfillmentGateway.class, () -> gateway);
                    return source == null
                            ? configured
                            : configured.withBean(ChannelFulfillmentSourcePort.class, () -> source);
                },
                s -> {
                    s.sql()
                            .execute(
                                    "UPDATE cim_authorization_revision SET"
                                        + " granted_scopes='[\"CATALOG\",\"ORDER\",\"FULFILLMENT\"]'");
                    var template = original.original("R1", 1, "Original");
                    var line = template.lines().getFirst();
                    var d =
                            new ChannelOrderGateway.Document(
                                    template.schemaVersion(),
                                    template.externalAccountRef(),
                                    template.externalStoreRef(),
                                    template.externalOrderRef(),
                                    template.revisionRef(),
                                    template.revisionSequence(),
                                    template.catalogHash(),
                                    template.currency(),
                                    template.state(),
                                    template.paymentState(),
                                    template.originalPaymentRef(),
                                    template.fulfillment(),
                                    template.orderedAt(),
                                    template.requestedAt(),
                                    template.customer(),
                                    List.of(
                                            new ChannelOrderGateway.Line(
                                                    line.lineRef(),
                                                    line.offerIdentity(),
                                                    line.skuIdentity(),
                                                    quantity,
                                                    supplierUnit,
                                                    line.grossExTaxMinor(),
                                                    line.merchantDiscountExTaxMinor(),
                                                    line.taxMinor(),
                                                    line.netMinor(),
                                                    line.selections(),
                                                    line.note())),
                                    template.totals());
                    var now = Instant.now();
                    var snapshot =
                            new ChannelOrderGateway.Snapshot(
                                    d,
                                    now,
                                    now.plusSeconds(25),
                                    "ORIGINAL_PLATFORM",
                                    PricingSnapshotHash.of(d));
                    var command =
                            new ChannelOrderIngestPort.Command(
                                    "IMPORT", "E", "S", "CON", "CR", "REV1", snapshot, List.of());
                    s.sql()
                            .update(
                                    """
                                    UPDATE cim_order_link SET source_order_ref='NATIVE',core_order_ref='CORE',import_status='CONFIRMED',
                                        acceptance_status='CONFIRMED',import_snapshot=?
                                    """,
                                    json.writeValueAsString(command));
                    s.sql()
                            .update(
                                    """
                                    INSERT INTO cim_order_line_link(link_no,entity_no,channel_order_no,order_revision_no,external_line_ref,
                                        component_kind,component_ref,source_line_ref,core_order_ref,core_item_ref,sale_quantity,
                                        quantity_unit,amount_minor,currency,allocation_evidence_ref)
                                    VALUES ('ALLOCATION','E','ORDER','REV1','LINE','MAIN','MAIN_COMPONENT','NATIVE_LINE',
                                        'CORE','OPAQUE_ITEM',?,?,100,'CNY','ORIGINAL_CORE_ALLOCATION')
                                    """,
                                    new BigDecimal(quantity),
                                    allocationUnit);
                    assertions.accept(s);
                });
    }

    @Test
    void originalPieceAliasPublishesSupplierUnitWithoutChangingCanonicalAllocation() {
        var source = new Source();
        source.unit = "PIECE";
        source.quantity = "1";
        var gateway = new Gateway();
        run(
                source,
                gateway,
                "EA",
                "PIECE",
                "1",
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    var worker = s.context().getBean(ChannelFulfillmentServiceImpl.class);
                    api.publishFulfillment("E", "S", "A", request(), "piece-alias");
                    var frozen =
                            json.readValue(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT payload_json FROM cim_fulfillment_link",
                                                    String.class),
                                    ChannelFulfillmentGateway.FulfillmentPublication.class);
                    assertThat(frozen.lines())
                            .singleElement()
                            .satisfies(
                                    line -> {
                                        assertThat(line.unit()).isEqualTo("EA");
                                        assertThat(new BigDecimal(line.quantity()))
                                                .isEqualByComparingTo("1");
                                    });
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT quantity_unit FROM cim_order_line_link",
                                                    String.class))
                            .isEqualTo("PIECE");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT unit FROM cim_fulfillment_line_link",
                                                    String.class))
                            .isEqualTo("PIECE");
                    assertThat(gateway.sends).isZero();
                    worker.recoverDue(10);
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.sent).isEqualTo(frozen);
                    source.unavailable = true;
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                    worker.recoverDue(10);
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.queries).isOne();
                    assertThat(gateway.queried).isEqualTo(frozen);
                });
    }

    @Test
    void unsupportedWireUnitsCannotConvertOrRewriteCanonicalSourceQuantities() {
        for (var units : List.of(List.of("KG", "G"), List.of("G", "KG"), List.of("EA", "KG"))) {
            var source = new Source();
            source.unit = units.get(1);
            source.quantity = "1";
            var gateway = new Gateway();
            run(
                    source,
                    gateway,
                    units.get(0),
                    units.get(1),
                    "1",
                    s -> {
                        var api = s.context().getBean(ChannelFulfillmentService.class);
                        assertThatThrownBy(
                                        () ->
                                                api.publishFulfillment(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        request(),
                                                        "unsupported-unit"))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(
                                        s.sql()
                                                .queryForObject(
                                                        "SELECT COUNT(*) FROM cim_fulfillment_link",
                                                        Integer.class))
                                .isZero();
                        assertThat(
                                        s.sql()
                                                .queryForObject(
                                                        "SELECT quantity_unit FROM"
                                                            + " cim_order_line_link",
                                                        String.class))
                                .isEqualTo(units.get(1));
                        assertThat(gateway.sends).isZero();
                    });
        }
    }

    @Test
    void originalFactAndActionCommitBeforeSendAndUnknownRecoveryDoesNotNeedTheOriginalOwnerAgain() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    var worker = s.context().getBean(ChannelFulfillmentServiceImpl.class);
                    var operation = api.publishFulfillment("E", "S", "A", request(), "publish");
                    assertThat(gateway.sends).isZero();
                    source.unavailable = true;
                    assertThat(api.publishFulfillment("E", "S", "A", request(), "publish"))
                            .isEqualTo(operation);
                    source.unavailable = false;
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isOne();
                    source.unavailable = true;
                    int sourceCalls = source.calls;
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(worker.recoverDue(10)).isOne();
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.queries).isOne();
                    assertThat(source.calls).isEqualTo(sourceCalls);
                    assertThat(
                                    s.sql()
                                            .queryForList(
                                                    "SELECT attempt_kind FROM cim_operation_attempt"
                                                            + " ORDER BY id",
                                                    String.class))
                            .containsExactly("SEND", "QUERY");
                    var result =
                            api.listFulfillments("E", "S", "ORDER", 1, 20).records().getFirst();
                    assertThat(result.status()).isEqualTo("SUCCEEDED");
                    assertThat(result.stage()).isEqualTo("READY");
                    assertThat(result.lines().getFirst().quantity()).isEqualTo("1.25");
                    assertThat(json.writeValueAsString(result))
                            .doesNotContain("OPAQUE_ITEM", "vault:ref", "payloadJson");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT fulfillment_status FROM cim_order_link",
                                                    String.class))
                            .isEqualTo("UNCONFIRMED");
                });
    }

    @Test
    void aMissingOwnerOrExcessPhysicalQuantityCannotBePublishedAsAClientReportedFact() {
        var gateway = new Gateway();
        run(
                null,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    assertThatThrownBy(
                                    () ->
                                            api.publishFulfillment(
                                                    "E", "S", "A", request(), "missing"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_fulfillment_link",
                                                    Integer.class))
                            .isZero();
                });
        var source = new Source();
        source.quantity = "2";
        run(
                source,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    assertThatThrownBy(
                                    () ->
                                            api.publishFulfillment(
                                                    "E", "S", "A", request(), "excess"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation",
                                                    Integer.class))
                            .isZero();
                });
        assertThat(gateway.sends).isZero();
    }

    @Test
    void changedOriginalRevisionOrRotatedGrantCannotAuthorizeTheFirstSend() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    var worker = s.context().getBean(ChannelFulfillmentServiceImpl.class);
                    api.publishFulfillment("E", "S", "A", request(), "publish");
                    source.stage = Stage.DELIVERED;
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                    source.stage = Stage.READY;
                    s.sql().execute("UPDATE cim_account SET active_authorization_no='OTHER'");
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt",
                                                    Integer.class))
                            .isZero();
                });
    }

    @Test
    void lateProviderReceiptCannotOverwriteANewerFenceAndStoreQueriesAreScoped() {
        var source = new Source();
        var gateway = new Gateway();
        gateway.timeout = false;
        run(
                source,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelFulfillmentService.class);
                    api.publishFulfillment("E", "S", "A", request(), "publish");
                    gateway.during =
                            () ->
                                    s.sql()
                                            .execute(
                                                    "UPDATE cim_operation SET"
                                                        + " fence=fence+1,current_attempt_no=current_attempt_no+1");
                    assertThat(
                                    s.context()
                                            .getBean(ChannelFulfillmentServiceImpl.class)
                                            .recoverDue(10))
                            .isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_fulfillment_link",
                                                    String.class))
                            .isEqualTo("QUEUED");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT late_evidence_ref FROM"
                                                            + " cim_operation_attempt",
                                                    String.class))
                            .isEqualTo("MOCK_PLATFORM_FULFILLMENT_RECEIPT");
                    assertThatThrownBy(() -> api.listFulfillments("E", "OTHER", "ORDER", 1, 20))
                            .isInstanceOf(RuntimeException.class);
                });
    }
}
