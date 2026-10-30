package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Real loopback supplier persistence/restart; source/funds references are explicit isolated fixture
 * inputs only.
 */
class MockAfterSaleHttpTest {
    @TempDir Path directory;
    final MockCommercePlatformTest fixture = new MockCommercePlatformTest();

    MockCommercePlatform server() throws Exception {
        fixture.directory = directory;
        return fixture.server();
    }

    void setup(MockCommercePlatform server) throws Exception {
        fixture.post(
                server,
                "admin/fixtures",
                new MockCommercePlatform.Fixture(
                        "E",
                        "S",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "MOCK:TW",
                        Set.of("CATALOG", "ORDER", "AFTER_SALE"),
                        MockCommercePlatform.Scenario.SUCCESS));
    }

    ChannelOrderGateway.Document accepted(String number) {
        var d = fixture.externalOrder(number);
        return new ChannelOrderGateway.Document(
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
    }

    ChannelAfterSaleGateway.Document afterSale(
            ChannelOrderGateway.Document order, String caseRef, String quantity, long amount) {
        return new ChannelAfterSaleGateway.Document(
                "EA",
                "ES",
                order.externalOrderRef(),
                caseRef,
                "AR1",
                1L,
                ChannelAfterSaleGateway.Type.REFUND_ONLY,
                ChannelAfterSaleGateway.State.REQUESTED,
                "AED",
                amount,
                List.of(new ChannelAfterSaleGateway.RequestedLine("LINE1", quantity, "KG", amount)),
                Instant.now());
    }

    ChannelAfterSaleGateway.Publication publication(
            String op,
            String credential,
            ChannelOrderGateway.Document order,
            ChannelAfterSaleGateway.Document request,
            String authorization) {
        var scope =
                new ChannelOrderGateway.Query(
                        "E",
                        "S",
                        "CON",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        order.externalOrderRef(),
                        order.revisionRef(),
                        "AUTH",
                        credential);
        return new ChannelAfterSaleGateway.Publication(
                op,
                scope,
                request.externalCaseRef(),
                request.revisionRef(),
                PricingSnapshotHash.of(request),
                PricingSnapshotHash.of(order),
                "ORIGINAL_DECISION_FIXTURE",
                "a".repeat(64),
                "a".repeat(64),
                ChannelAfterSaleGateway.Action.REFUND_ONLY,
                "ORIGINAL_POLICY",
                "AED",
                request.merchantRefundMinor(),
                request.lines().stream()
                        .map(
                                l ->
                                        new ChannelAfterSaleGateway.DecisionLine(
                                                l.externalLineRef(),
                                                l.quantity(),
                                                l.unit(),
                                                l.merchantRefundMinor()))
                        .toList(),
                authorization);
    }

    @Test
    void supplierReadAndTimeoutWriteSurviveRestartAndRevocationWithoutAnotherEffect()
            throws Exception {
        ChannelAfterSaleGateway.Publication publication;
        try (var server = server()) {
            setup(server);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var original = accepted("ORDER_AS");
            fixture.post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.SUCCESS));
            var requested = afterSale(original, "CASE_AS", "0.5", 250);
            fixture.post(
                    server,
                    "admin/after-sales",
                    new MockCommercePlatform.AfterSaleSeed(
                            "MOCK_PROFILE",
                            requested,
                            MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT));
            publication =
                    publication(
                            "AS_OP",
                            credential,
                            original,
                            requested,
                            "INDEPENDENT_FUNDS_AUTH_FIXTURE");
            assertThat(gateway.readAfterSales(publication.scope()).getFirst().document())
                    .isEqualTo(requested);
            assertThat(gateway.applyAfterSale(publication).status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
            gateway.revoke(
                    new ChannelAuthorizationGateway.RevocationContext(
                            "REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", credential));
        }
        try (var server = server()) {
            var gateway = fixture.gateway(server);
            var receipt = gateway.queryAfterSale(publication);
            assertThat(receipt.status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.ACKNOWLEDGED);
            assertThat(gateway.queryAfterSale(publication)).isEqualTo(receipt);
            assertThat(receipt.publicationHash()).isEqualTo(PricingSnapshotHash.of(publication));
            assertThat(gateway.applyAfterSale(publication).status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
        }
    }

    @Test
    void noFundsAuthorizationCannotBecomeSupplierRefundSuccess() throws Exception {
        try (var server = server()) {
            setup(server);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var original = accepted("NO_FUNDS");
            fixture.post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.SUCCESS));
            var requested = afterSale(original, "CASE_NO_FUNDS", "0.5", 250);
            fixture.post(
                    server,
                    "admin/after-sales",
                    new MockCommercePlatform.AfterSaleSeed(
                            "MOCK_PROFILE", requested, MockCommercePlatform.Scenario.SUCCESS));
            var p = publication("NO_FUNDS_OP", credential, original, requested, null);
            assertThat(gateway.applyAfterSale(p).status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
            assertThat(gateway.queryAfterSale(p).status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
        }
    }

    @Test
    void cumulativeSupplierCasesCannotExceedOriginalPhysicalInvoiceQuantity() throws Exception {
        try (var server = server()) {
            setup(server);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var original = accepted("CUMULATIVE");
            fixture.post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.SUCCESS));
            var first = afterSale(original, "CASE_ONE", "0.75", 300);
            var second = afterSale(original, "CASE_TWO", "0.75", 300);
            fixture.post(
                    server,
                    "admin/after-sales",
                    new MockCommercePlatform.AfterSaleSeed(
                            "MOCK_PROFILE", first, MockCommercePlatform.Scenario.SUCCESS));
            fixture.post(
                    server,
                    "admin/after-sales",
                    new MockCommercePlatform.AfterSaleSeed(
                            "MOCK_PROFILE", second, MockCommercePlatform.Scenario.SUCCESS));
            assertThat(
                            gateway.applyAfterSale(
                                            publication(
                                                    "ONE",
                                                    credential,
                                                    original,
                                                    first,
                                                    "FUNDS_ONE"))
                                    .status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.ACKNOWLEDGED);
            assertThat(
                            gateway.applyAfterSale(
                                            publication(
                                                    "TWO",
                                                    credential,
                                                    original,
                                                    second,
                                                    "FUNDS_TWO"))
                                    .status())
                    .isEqualTo(ChannelAfterSaleGateway.ResultStatus.REJECTED);
        }
    }
}
