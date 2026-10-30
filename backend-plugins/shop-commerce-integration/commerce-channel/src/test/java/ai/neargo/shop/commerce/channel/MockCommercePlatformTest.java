package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.ChannelFulfillmentPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentLine;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentPublication;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationStatus;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

class MockCommercePlatformTest {
    static final String TOKEN = "mock-token-only-for-local-platform-tests";
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    @TempDir Path directory;

    @Test
    void originalContractsCrossHttpAndResourceIdentityHasExactParents() throws Exception {
        try (var server = server()) {
            var gateway = gateway(server);
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var credential = authorize(gateway);
            var identity =
                    new ChannelConnectionIdentityPort.Identity(
                            "E", "S", "A", "MOCK_PROFILE", "EA", "ES", "MOCK:TW", false, 0, 0);
            assertThat(gateway.verify(identity).status())
                    .isEqualTo(ChannelConnectionIdentityPort.Status.VERIFIED);
            var publication = publication("OP1", credential);
            var result = gateway.publish(publication);
            assertThat(result.outcome()).isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
            var menu =
                    result.items().stream()
                            .filter(i -> "MENU".equals(i.objectType()))
                            .findFirst()
                            .orElseThrow();
            var query =
                    new ChannelResourceIdentityGateway.Query(
                            "VERIFY",
                            "E",
                            "S",
                            publication.connectionNo(),
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "AUTH",
                            credential,
                            ChannelResourceIdentityGateway.ObjectType.MENU,
                            new ChannelResourceIdentityGateway.Identity(
                                    menu.externalRef(), List.of()));
            assertThat(gateway.query(query).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.VERIFIED);
            var wrong =
                    new ChannelResourceIdentityGateway.Query(
                            query.operationNo(),
                            "E",
                            "S",
                            publication.connectionNo(),
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "AUTH",
                            credential,
                            query.objectType(),
                            new ChannelResourceIdentityGateway.Identity(
                                    menu.externalRef(),
                                    List.of(
                                            new ChannelResourceIdentityGateway.Parent(
                                                    query.objectType(), "OTHER"))));
            assertThat(gateway.query(wrong).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.NOT_FOUND);
        }
    }

    @Test
    void asyncUnknownAndPartialStayDistinctAndUnconfirmedEffectsCanBeResolved() throws Exception {
        try (var server = server()) {
            var gateway = gateway(server);
            for (var scenario : MockCommercePlatform.Scenario.values()) {
                seed(server, scenario);
                var credential = authorize(gateway);
                var publication = publication("OP" + scenario, credential);
                var result = gateway.publish(publication);
                switch (scenario) {
                    case ASYNC -> {
                        assertThat(result.outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.SUBMITTED);
                        assertThat(gateway.query(publication, publication.operationNo()).outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.SUBMITTED);
                        assertThat(gateway.query(publication, publication.operationNo()).outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
                    }
                    case UNKNOWN -> {
                        assertThat(result.allWritesTerminal()).isFalse();
                        post(
                                server,
                                "admin/operations/resolve",
                                new MockCommercePlatform.Resolve(
                                        publication.operationNo(),
                                        ChannelCatalogGateway.Outcome.SUCCEEDED));
                        assertThat(gateway.query(publication, publication.operationNo()).outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
                    }
                    case PERSIST_THEN_TIMEOUT -> {
                        assertThat(result.outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
                        assertThat(gateway.query(publication, publication.operationNo()).outcome())
                                .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
                    }
                    case PARTIAL ->
                            assertThat(result.outcome())
                                    .isEqualTo(ChannelCatalogGateway.Outcome.PARTIAL);
                    case REJECT ->
                            assertThat(result.outcome())
                                    .isEqualTo(ChannelCatalogGateway.Outcome.FAILED);
                    default ->
                            assertThat(result.outcome())
                                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
                }
            }
        }
    }

    @Test
    void restartKeepsCommittedExternalEffectsAndDoesNotRepublishAnUnknownOperation()
            throws Exception {
        ChannelCatalogGateway.Publication publication;
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT);
            var gateway = gateway(server);
            publication = publication("RESTORE", authorize(gateway));
            assertThat(gateway.publish(publication).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
        }
        try (var server = server()) {
            assertThat(gateway(server).query(publication, publication.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
        }
    }

    @Test
    void
            originalOrderHttpPreservesDecimalComponentsAndQueriesAnUnknownAcceptanceWithoutSendingAgain()
                    throws Exception {
        ChannelOrderGateway.Decision decision;
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT);
            var gateway = gateway(server);
            var credential = authorize(gateway);
            var original = externalOrder("ORDER1");
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE",
                            original,
                            MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT));
            var query =
                    new ChannelOrderGateway.Query(
                            "E",
                            "S",
                            "CON",
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "ORDER1",
                            "R1",
                            "AUTH",
                            credential);
            var snapshot = gateway.readOrder(query);
            ChannelOrderPolicy.validate(query, snapshot, Clock.systemUTC());
            assertThat(snapshot.document()).isEqualTo(original);
            decision =
                    new ChannelOrderGateway.Decision(
                            "ACCEPT1",
                            query,
                            snapshot.documentHash(),
                            "R1",
                            ChannelOrderGateway.Action.ACCEPT,
                            null,
                            null);
            assertThat(gateway.decide(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.UNKNOWN);
        }
        try (var server = server()) {
            var gateway = gateway(server);
            assertThat(gateway.queryDecision(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.CONFIRMED);
            var q = decision.scope();
            var latest =
                    gateway.readOrder(
                            new ChannelOrderGateway.Query(
                                    q.entityNo(),
                                    q.storeNo(),
                                    q.connectionNo(),
                                    q.profileNo(),
                                    q.externalAccountRef(),
                                    q.externalStoreRef(),
                                    q.externalOrderRef(),
                                    null,
                                    q.authorizationNo(),
                                    q.credentialRef()));
            assertThat(latest.document().state()).isEqualTo(ChannelOrderGateway.State.ACCEPTED);
            assertThat(latest.document().revisionSequence()).isEqualTo(2L);
            assertThat(gateway.readOrder(q).document().state())
                    .isEqualTo(ChannelOrderGateway.State.WAITING_ACCEPTANCE);
        }
    }

    @Test
    void orderArithmeticScopeAndRevocationCannotBeBypassedByAValidLookingMockPayload()
            throws Exception {
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            var credential = authorize(gateway);
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE",
                            externalOrder("ORDER2"),
                            MockCommercePlatform.Scenario.SUCCESS));
            var query =
                    new ChannelOrderGateway.Query(
                            "E",
                            "S",
                            "CON",
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "ORDER2",
                            "R1",
                            "AUTH",
                            credential);
            var snapshot = gateway.readOrder(query);
            var wrong =
                    new ChannelOrderGateway.Query(
                            "E",
                            "OTHER",
                            "CON",
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "ORDER2",
                            "R1",
                            "AUTH",
                            credential);
            assertThatThrownBy(() -> gateway.readOrder(wrong)).isInstanceOf(RuntimeException.class);
            var d = snapshot.document();
            var bad =
                    new ChannelOrderGateway.Document(
                            d.schemaVersion(),
                            d.externalAccountRef(),
                            d.externalStoreRef(),
                            d.externalOrderRef(),
                            d.revisionRef(),
                            d.revisionSequence(),
                            d.catalogHash(),
                            d.currency(),
                            d.state(),
                            d.paymentState(),
                            d.originalPaymentRef(),
                            d.fulfillment(),
                            d.orderedAt(),
                            d.requestedAt(),
                            d.customer(),
                            d.lines(),
                            new ChannelOrderGateway.Totals(0, 100, 20, 50, 99999, 725));
            var now = Instant.now();
            assertThatThrownBy(
                            () ->
                                    ChannelOrderPolicy.validate(
                                            query,
                                            new ChannelOrderGateway.Snapshot(
                                                    bad,
                                                    now,
                                                    now.plusSeconds(20),
                                                    "ORIGINAL",
                                                    PricingSnapshotHash.of(bad)),
                                            Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
            gateway.revoke(
                    new ChannelAuthorizationGateway.RevocationContext(
                            "REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", credential));
            assertThatThrownBy(() -> gateway.readOrder(query)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void readOnlyRecoveryCompletesACommittedCatalogActionAfterCrashBeforeProjection()
            throws Exception {
        ChannelCatalogGateway.Publication publication;
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            publication = publication("CRASH", authorize(gateway));
            server.failAfterCommitOnce(publication.operationNo());
            assertThat(gateway.publish(publication).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
        }
        try (var server = server()) {
            var gateway = gateway(server);
            var result = gateway.query(publication, publication.operationNo());
            assertThat(result.outcome()).isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
            var menu =
                    result.items().stream()
                            .filter(i -> "MENU".equals(i.objectType()))
                            .findFirst()
                            .orElseThrow();
            var query =
                    new ChannelResourceIdentityGateway.Query(
                            "CHECK",
                            "E",
                            "S",
                            publication.connectionNo(),
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "AUTH",
                            publication.credentialRef(),
                            ChannelResourceIdentityGateway.ObjectType.MENU,
                            new ChannelResourceIdentityGateway.Identity(
                                    menu.externalRef(), List.of()));
            assertThat(gateway.query(query).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.VERIFIED);
            assertThat(gateway.query(publication, publication.operationNo())).isEqualTo(result);
        }
    }

    @Test
    void anOlderAsyncGenerationCannotOverwriteANewerCommittedCatalog() throws Exception {
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.ASYNC);
            var gateway = gateway(server);
            var original = publication("OLD", authorize(gateway));
            var old = withScope(original, "SHARED", 1);
            assertThat(gateway.publish(old).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUBMITTED);
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var newer = withScope(publication("NEW", old.credentialRef()), "SHARED", 2);
            assertThat(gateway.publish(newer).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
            assertThat(gateway.query(old, old.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.CANCELLED);
            assertThat(gateway.query(old, old.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.CANCELLED);
            assertThat(gateway.query(newer, newer.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
        }
    }

    @Test
    void aCommittedOrderDecisionAndRevocationCanBeRecoveredAcrossTheProjectionCrashGap()
            throws Exception {
        ChannelOrderGateway.Decision decision;
        String credential;
        var revoke =
                new ChannelAuthorizationGateway.RevocationContext(
                        "CRASH_REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", "MOCK:AUTH");
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            credential = authorize(gateway);
            assertThat(credential).isEqualTo(revoke.credentialRef());
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE",
                            externalOrder("GAP"),
                            MockCommercePlatform.Scenario.SUCCESS));
            var query =
                    new ChannelOrderGateway.Query(
                            "E",
                            "S",
                            "CON",
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "GAP",
                            "R1",
                            "AUTH",
                            credential);
            var snapshot = gateway.readOrder(query);
            decision =
                    new ChannelOrderGateway.Decision(
                            "CRASH_ACCEPT",
                            query,
                            snapshot.documentHash(),
                            "R1",
                            ChannelOrderGateway.Action.ACCEPT,
                            null,
                            null);
            server.failAfterCommitOnce(decision.operationNo());
            assertThat(gateway.decide(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.UNKNOWN);
        }
        try (var server = server()) {
            var gateway = gateway(server);
            assertThat(gateway.queryDecision(decision).status())
                    .isEqualTo(ChannelOrderGateway.DecisionStatus.CONFIRMED);
            var q = decision.scope();
            assertThat(
                            gateway.readOrder(
                                            new ChannelOrderGateway.Query(
                                                    q.entityNo(),
                                                    q.storeNo(),
                                                    q.connectionNo(),
                                                    q.profileNo(),
                                                    q.externalAccountRef(),
                                                    q.externalStoreRef(),
                                                    q.externalOrderRef(),
                                                    null,
                                                    q.authorizationNo(),
                                                    q.credentialRef()))
                                    .document()
                                    .state())
                    .isEqualTo(ChannelOrderGateway.State.ACCEPTED);
            server.failAfterCommitOnce(revoke.operationNo());
            assertThatThrownBy(() -> gateway.revoke(revoke)).isInstanceOf(RuntimeException.class);
        }
        try (var server = server()) {
            var gateway = gateway(server);
            assertThat(gateway.queryRevocation(revoke).status())
                    .isEqualTo(ChannelAuthorizationGateway.RecoveryStatus.FOUND);
            assertThatThrownBy(() -> gateway.readOrder(decision.scope()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    ChannelCatalogGateway.Publication withScope(
            ChannelCatalogGateway.Publication p, String connection, long generation) {
        return new ChannelCatalogGateway.Publication(
                p.operationNo(),
                generation,
                p.entityNo(),
                p.storeNo(),
                connection,
                p.externalStoreRef(),
                p.remoteScopeKey(),
                p.authorizationNo(),
                p.credentialRef(),
                p.contentHash(),
                p.document());
    }

    @Test
    void splitShipmentsPreservePhysicalQuantitiesAndDoNotTurnShipmentIntoDelivery()
            throws Exception {
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            var credential = authorize(gateway);
            var original = acceptedOrder("PARCELS", ChannelOrderGateway.Fulfillment.SHIPMENT);
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.SUCCESS));
            var first =
                    fulfillment(
                            "SHIP1",
                            credential,
                            original,
                            "FULFILL1",
                            "R1",
                            Stage.SHIPPED,
                            "PACKAGE1",
                            "0.5");
            var second =
                    fulfillment(
                            "SHIP2",
                            credential,
                            original,
                            "FULFILL2",
                            "R1",
                            Stage.SHIPPED,
                            "PACKAGE2",
                            "0.75");
            assertThat(ChannelFulfillmentPolicy.confirms(first, gateway.publishFulfillment(first)))
                    .isTrue();
            assertThat(
                            ChannelFulfillmentPolicy.confirms(
                                    second, gateway.publishFulfillment(second)))
                    .isTrue();
            assertThat(gateway.publishFulfillment(second))
                    .isEqualTo(gateway.queryFulfillment(second));
            var excess =
                    fulfillment(
                            "EXCESS",
                            credential,
                            original,
                            "FULFILL3",
                            "R1",
                            Stage.SHIPPED,
                            "PACKAGE3",
                            "0.01");
            assertThat(gateway.publishFulfillment(excess).status())
                    .isEqualTo(PublicationStatus.REJECTED);
            var delivered =
                    fulfillment(
                            "DELIVER1",
                            credential,
                            original,
                            "FULFILL1",
                            "R2",
                            Stage.DELIVERED,
                            "PACKAGE1",
                            "0.5");
            assertThat(
                            ChannelFulfillmentPolicy.confirms(
                                    delivered, gateway.publishFulfillment(delivered)))
                    .isTrue();
            assertThat(gateway.queryFulfillment(first).status())
                    .isEqualTo(PublicationStatus.CONFIRMED);
            var regression =
                    fulfillment(
                            "REGRESS",
                            credential,
                            original,
                            "FULFILL1",
                            "R3",
                            Stage.SHIPPED,
                            "PACKAGE1",
                            "0.5");
            assertThat(gateway.publishFulfillment(regression).status())
                    .isEqualTo(PublicationStatus.REJECTED);
            var wrongPackage =
                    fulfillment(
                            "DUP_PACKAGE",
                            credential,
                            original,
                            "FULFILL_OTHER",
                            "R1",
                            Stage.SHIPPED,
                            "PACKAGE2",
                            "0.01");
            assertThat(gateway.publishFulfillment(wrongPackage).status())
                    .isEqualTo(PublicationStatus.REJECTED);
            assertThat(gateway.readOrder(first.scope()).document().state())
                    .isEqualTo(ChannelOrderGateway.State.ACCEPTED);
        }
    }

    @Test
    void fulfillmentCommitsOnceAndReadsBackTheSameReceiptAfterTimeoutAndRestart() throws Exception {
        FulfillmentPublication publication;
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            var credential = authorize(gateway);
            var original =
                    acceptedOrder("HANDOVER", ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY);
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.SUCCESS));
            publication =
                    fulfillment(
                            "CRASH_FULFILL",
                            credential,
                            original,
                            "NATIVE_HANDOVER",
                            "R1",
                            Stage.HANDED_OVER,
                            null,
                            "1.25");
            server.failAfterCommitOnce(publication.operationNo());
            assertThat(gateway.publishFulfillment(publication).status())
                    .isEqualTo(PublicationStatus.UNKNOWN);
        }
        try (var server = server()) {
            var gateway = gateway(server);
            var receipt = gateway.queryFulfillment(publication);
            assertThat(ChannelFulfillmentPolicy.confirms(publication, receipt)).isTrue();
            assertThat(gateway.queryFulfillment(publication)).isEqualTo(receipt);
            assertThat(gateway.readOrder(publication.scope()).document().state())
                    .isEqualTo(ChannelOrderGateway.State.ACCEPTED);
        }
    }

    @Test
    void asynchronousFulfillmentUsesOriginalQueriesAndUnknownRequiresExplicitMockOwnerResolution()
            throws Exception {
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = gateway(server);
            var credential = authorize(gateway);
            var original = acceptedOrder("ASYNC_FULFILL", ChannelOrderGateway.Fulfillment.PICKUP);
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", original, MockCommercePlatform.Scenario.ASYNC));
            var publication =
                    fulfillment(
                            "ASYNC_PICKUP",
                            credential,
                            original,
                            "ORIGINAL_PICKUP",
                            "R1",
                            Stage.PICKED_UP,
                            null,
                            "1.25");
            assertThat(gateway.publishFulfillment(publication).status())
                    .isEqualTo(PublicationStatus.UNKNOWN);
            assertThat(gateway.queryFulfillment(publication).status())
                    .isEqualTo(PublicationStatus.UNKNOWN);
            assertThat(
                            ChannelFulfillmentPolicy.confirms(
                                    publication, gateway.queryFulfillment(publication)))
                    .isTrue();
            var unknownOrder =
                    acceptedOrder("UNKNOWN_FULFILL", ChannelOrderGateway.Fulfillment.PICKUP);
            post(
                    server,
                    "admin/orders",
                    new MockCommercePlatform.OrderSeed(
                            "MOCK_PROFILE", unknownOrder, MockCommercePlatform.Scenario.UNKNOWN));
            var unknown =
                    fulfillment(
                            "UNKNOWN_PICKUP",
                            credential,
                            unknownOrder,
                            "ORIGINAL_PICKUP2",
                            "R1",
                            Stage.READY,
                            null,
                            "1.25");
            assertThat(gateway.publishFulfillment(unknown).status())
                    .isEqualTo(PublicationStatus.UNKNOWN);
            assertThat(gateway.queryFulfillment(unknown).status())
                    .isEqualTo(PublicationStatus.UNKNOWN);
            post(
                    server,
                    "admin/fulfillments/resolve",
                    new MockCommercePlatform.ResolveFulfillment(
                            unknown, PublicationStatus.CONFIRMED));
            assertThat(
                            ChannelFulfillmentPolicy.confirms(
                                    unknown, gateway.queryFulfillment(unknown)))
                    .isTrue();
            assertThat(
                            gateway.supportsFulfillment(
                                    ChannelOrderGateway.Fulfillment.PICKUP, Stage.DELIVERED))
                    .isFalse();
            assertThat(
                            gateway.supportsFulfillment(
                                    ChannelOrderGateway.Fulfillment.SHIPMENT, Stage.READY))
                    .isFalse();
        }
    }

    ChannelOrderGateway.Document acceptedOrder(
            String number, ChannelOrderGateway.Fulfillment fulfillment) {
        var d = externalOrder(number);
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
                fulfillment,
                d.orderedAt(),
                d.requestedAt(),
                d.customer(),
                d.lines(),
                d.totals());
    }

    FulfillmentPublication fulfillment(
            String operation,
            String credential,
            ChannelOrderGateway.Document d,
            String originalFulfillment,
            String revision,
            Stage stage,
            String parcel,
            String quantity) {
        var scope =
                new ChannelOrderGateway.Query(
                        "E",
                        "S",
                        "CON",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        d.externalOrderRef(),
                        d.revisionRef(),
                        "AUTH",
                        credential);
        return new FulfillmentPublication(
                operation,
                scope,
                PricingSnapshotHash.of(d),
                originalFulfillment,
                revision,
                stage,
                parcel,
                parcel == null ? null : "MOCK_CARRIER",
                parcel == null ? null : "TRACKING:" + parcel,
                List.of(new FulfillmentLine("LINE1", quantity, "KG")),
                Instant.now(),
                "MOCK_NATIVE:" + originalFulfillment);
    }

    ChannelOrderGateway.Document externalOrder(String number) {
        var offer = new ChannelResourceIdentityGateway.Identity("REMOTE_OFFER", List.of());
        var sku =
                new ChannelResourceIdentityGateway.Identity(
                        "REMOTE_SKU",
                        List.of(
                                new ChannelResourceIdentityGateway.Parent(
                                        ChannelResourceIdentityGateway.ObjectType.OFFER,
                                        "REMOTE_OFFER")));
        var line =
                new ChannelOrderGateway.Line(
                        "LINE1",
                        offer,
                        sku,
                        "1.25",
                        "KG",
                        750,
                        100,
                        65,
                        715,
                        List.of(
                                new ChannelOrderGateway.Selection(
                                        "TOPPING1", "OPTION", sku, "0.5", 25)),
                        "No onion");
        return new ChannelOrderGateway.Document(
                1,
                "EA",
                "ES",
                number,
                "R1",
                1L,
                "a".repeat(64),
                "AED",
                ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                ChannelOrderGateway.PaymentState.PLATFORM_PAID,
                "REMOTE_PAY1",
                ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                Instant.now(),
                null,
                new ChannelOrderGateway.Customer("CUSTOMER1", "Guest", "12345678", "Mock address"),
                List.of(line),
                new ChannelOrderGateway.Totals(40, 100, 20, 50, 755, 725));
    }

    MockCommercePlatform server() throws Exception {
        var server = new MockCommercePlatform(json, directory, TOKEN, 0);
        server.start();
        return server;
    }

    MockCommerceGateway gateway(MockCommercePlatform server) {
        return new MockCommerceGateway(
                "MOCK_PROFILE", URI.create("http://localhost:" + server.port()), TOKEN, json);
    }

    void seed(MockCommercePlatform server, MockCommercePlatform.Scenario scenario)
            throws Exception {
        post(
                server,
                "admin/fixtures",
                new MockCommercePlatform.Fixture(
                        "E",
                        "S",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "MOCK:TW",
                        Set.of("CATALOG", "ORDER", "FULFILLMENT"),
                        scenario));
    }

    void post(MockCommercePlatform server, String path, Object body) throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + server.port() + "/mock/v1/" + path))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                        .build();
        assertThat(
                        HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(200);
    }

    String authorize(MockCommerceGateway gateway) {
        var context =
                new ChannelAuthorizationGateway.AuthorizationContext(
                        "AUTH", "E", "A", "MOCK_PROFILE", "EA", "ACTOR", "STATE", "MOCK_MERCHANT");
        gateway.begin(context);
        return gateway.exchange(context, "mock-approved").credentialRef();
    }

    ChannelCatalogGateway.Publication publication(String operation, String credential) {
        var now = Instant.now();
        var proof =
                new ChannelSourceValidationPort.Validation(
                        ChannelSourceValidationPort.Status.VALID,
                        "M",
                        "R",
                        "a".repeat(64),
                        now.plusSeconds(25),
                        "OWNER");
        var sources =
                List.of(
                        new ChannelCatalogSourcePort.ValidatedSource(
                                ChannelSourceValidationPort.Owner.FOOD, "M", "R", 0, proof));
        var tax =
                new MerchantTaxSnapshot(
                        "MAIN",
                        "E",
                        "S",
                        1,
                        MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                        "E",
                        1,
                        500,
                        PriceTaxMode.EXCLUSIVE,
                        new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                                .snapshotHash("MERCHANT_CUSTOM:E", 1));
        var price =
                new ChannelCatalogDetails.UnitPrice(500L, "CNY", "PRICE", "REV", "TAX", "RECEIPT");
        var variant =
                new ChannelCatalogDetails.Variant(
                        "SKU",
                        "Dish",
                        price,
                        new ChannelCatalogDetails.Inventory("UNKNOWN", null, null),
                        List.of(),
                        List.of());
        var offer =
                new ChannelCatalogDetails.Offer(
                        "O", "M", "R", "G", "Dish", "CAT", List.of(variant), List.of(), "ITEM");
        var start = now.truncatedTo(ChronoUnit.DAYS);
        var document =
                new ChannelCatalogSourcePort.Document(
                        ChannelSourceValidationPort.Owner.FOOD,
                        "CNY",
                        sources,
                        List.of(),
                        List.of(),
                        List.of(),
                        now.plusSeconds(25),
                        "CAPTURE",
                        3,
                        new ChannelCatalogDetails(
                                List.of(
                                        new ChannelCatalogSourcePort.Section(
                                                "CAT",
                                                "Category",
                                                0,
                                                new ChannelCatalogSourcePort.SectionSource(
                                                        "M", "R", "CAT"))),
                                List.of(offer),
                                List.of()),
                        new CatalogAvailability(
                                start,
                                start.plus(7, ChronoUnit.DAYS),
                                List.of(
                                        new CatalogAvailability.SourceWindow(
                                                "M",
                                                "R",
                                                List.of(
                                                        new CatalogAvailability.UtcWindow(
                                                                start,
                                                                start.plus(7, ChronoUnit.DAYS)))))),
                        List.of(
                                new CatalogTaxBasis(
                                        "TAX",
                                        "E",
                                        "S",
                                        new CatalogPriceSourcePort.Market("CN", "CNY", 2),
                                        CatalogPriceSourceFacts.taxFact(tax))));
        return new ChannelCatalogGateway.Publication(
                operation,
                Math.abs((long) operation.hashCode()) + 1,
                "E",
                "S",
                "CON" + operation,
                "ES",
                "STORE",
                "AUTH",
                credential,
                CatalogDocumentPolicy.hash(document, json),
                document);
    }
}
