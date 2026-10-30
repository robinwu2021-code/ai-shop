package ai.neargo.shop.commerce.channel.mock;

import ai.neargo.shop.commerce.policy.ChannelFulfillmentPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityProvider;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelProductProfilePort;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.CommerceGateway;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The explicit mock adapter uses the same production workflow contracts over actual loopback HTTP.
 */
public final class MockCommerceGateway
        implements ChannelCatalogGateway,
                ChannelResourceIdentityGateway,
                ChannelAuthorizationGateway,
                ChannelConnectionIdentityProvider,
                ChannelInboundGateway,
                ChannelOrderGateway,
                ChannelProductProfilePort,
                ChannelFulfillmentGateway,
                ChannelAvailabilityGateway,
                ChannelStatementGateway,
                ChannelAfterSaleGateway,
                ChannelRedemptionGateway {
    public record Metadata(
            String market,
            String identityNamespace,
            String productCode,
            String protocolCode,
            boolean accountScoped) {}

    private Supplier<ChannelCertificateVaultPort> certificateVault = () -> null;

    /**
     * Only the actual in-process encrypted owner can materialize a controlled certificate for this
     * Mock protocol.
     */
    public void bindCertificateVault(Supplier<ChannelCertificateVaultPort> owner) {
        this.certificateVault = owner;
    }

    public static String certificateIdentity(
            String token, String profile, String account, byte[] code) {
        return MockCallbackSignatures.sign(
                token, "CERTIFICATE", PricingSnapshotHash.of(List.of(profile, account)), code);
    }

    private final Metadata metadata;
    private final String profile;
    private final URI base;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

    /**
     * Loopback and MOCK profile constraints prevent test credentials or a mock result from reaching
     * a supplier.
     */
    public MockCommerceGateway(String profile, URI base, String token, ObjectMapper json) {
        this(
                profile,
                base,
                token,
                json,
                new Metadata("TW", "MOCK:TW", "DELIVERY", "MOCK_V1", false));
    }

    public MockCommerceGateway(
            String profile, URI base, String token, ObjectMapper json, Metadata metadata) {
        if (metadata == null
                || metadata.market() == null
                || !metadata.market().matches("[A-Z]{2}")
                || metadata.identityNamespace() == null
                || !(metadata.identityNamespace().equals("MOCK:" + metadata.market())
                        || metadata.identityNamespace()
                                .startsWith("MOCK:" + metadata.market() + ":"))
                || metadata.protocolCode() == null
                || !metadata.protocolCode().startsWith("MOCK")
                || metadata.productCode() == null
                || !Set.of("DELIVERY", "RETAIL").contains(metadata.productCode()))
            throw new IllegalArgumentException("Mock metadata config");
        this.metadata = metadata;
        if (profile == null
                || !profile.startsWith("MOCK")
                || token == null
                || token.length() < 32
                || !"http".equals(base.getScheme())
                || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(base.getHost())
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null)
            throw new IllegalArgumentException(
                    "Mock gateway requires explicit loopback configuration");
        this.profile = profile;
        this.base = base;
        this.token = token;
        this.json = json;
    }

    @Override
    public String profileNo() {
        return profile;
    }

    @Override
    public Set<CommerceGateway.Capability> capabilities() {
        return Set.of(
                CommerceGateway.Capability.AUTHORIZATION,
                CommerceGateway.Capability.CATALOG,
                CommerceGateway.Capability.ORDER,
                CommerceGateway.Capability.FULFILLMENT,
                CommerceGateway.Capability.AVAILABILITY,
                CommerceGateway.Capability.STATEMENT,
                CommerceGateway.Capability.AFTER_SALE,
                CommerceGateway.Capability.REDEMPTION);
    }

    @Override
    public boolean supportsDocumentSchema(int version) {
        return version == 3;
    }

    @Override
    public boolean supportsEmptyReplacement() {
        return true;
    }

    @Override
    public boolean supportsValidatedWithdrawal() {
        return true;
    }

    @Override
    public boolean supportsQuery() {
        return true;
    }

    @Override
    public Set<String> returnTargetCodes() {
        return Set.of("MOCK_MERCHANT");
    }

    @Override
    public Set<String> authorizationOrigins() {
        return Set.of("https://commerce-mock.invalid");
    }

    @Override
    public Set<String> callbackUris() {
        return Set.of("https://commerce-mock.invalid/callback");
    }

    @Override
    public ChannelConnectionIdentityPort.Proof verify(
            ChannelConnectionIdentityPort.Identity identity) {
        if (!profile.equals(identity.profileNo())
                || !metadata.identityNamespace().equals(identity.identityNamespace())
                || metadata.accountScoped() != identity.accountScoped())
            throw new IllegalArgumentException("Wrong mock profile");
        return call("identity/verify", identity, ChannelConnectionIdentityPort.Proof.class);
    }

    private String definitionJson() {
        return "{\"mock\":true,\"capabilities\":[\"AUTHORIZATION\",\"CATALOG\",\"ORDER\",\"FULFILLMENT\","
                   + "\"AVAILABILITY\",\"STATEMENT\",\"AFTER_SALE\",\"REDEMPTION\"],\"documentSchemas\":[3]}";
    }

    @Override
    public ChannelProductProfilePort.Registration registration() {
        return new ChannelProductProfilePort.Registration(
                new ChannelProductProfilePort.Definition(
                        profile,
                        "MOCK",
                        metadata.productCode(),
                        metadata.market(),
                        metadata.protocolCode(),
                        definitionJson(),
                        1,
                        metadata.identityNamespace(),
                        metadata.accountScoped(),
                        true),
                "Mock platform");
    }

    @Override
    public ChannelProductProfilePort.Proof verifyDefinition(
            ChannelProductProfilePort.Definition definition) {
        var expected = registration().definition();
        boolean same =
                definition != null
                        && expected.profileNo().equals(definition.profileNo())
                        && expected.providerCode().equals(definition.providerCode())
                        && expected.productCode().equals(definition.productCode())
                        && expected.market().equals(definition.market())
                        && expected.protocolCode().equals(definition.protocolCode())
                        && expected.identitySchemaVersion() == definition.identitySchemaVersion()
                        && expected.identityNamespace().equals(definition.identityNamespace())
                        && expected.accountScoped() == definition.accountScoped()
                        && definition.mock()
                        && json.readTree(expected.capabilityDefinition())
                                .equals(json.readTree(definition.capabilityDefinition()));
        var now = Instant.now();
        return new ChannelProductProfilePort.Proof(
                same
                        ? ChannelProductProfilePort.Status.VERIFIED
                        : ChannelProductProfilePort.Status.UNSUPPORTED,
                definition,
                now,
                now.plusSeconds(30),
                same ? "MOCK:PROFILE:" + PricingSnapshotHash.of(definition) : null);
    }

    @Override
    public Start begin(AuthorizationContext context) {
        return call("authorization/begin", context, Start.class);
    }

    @Override
    public Grant exchange(AuthorizationContext context, String code) {
        return call(
                "authorization/exchange",
                new MockCommercePlatform.Exchange(context, code),
                Grant.class);
    }

    @Override
    public AuthorizationRecovery queryAuthorization(AuthorizationQuery query) {
        return call("authorization/query", query, AuthorizationRecovery.class);
    }

    @Override
    public RevocationRecovery queryRevocation(RevocationContext query) {
        return call("authorization/revocation-query", query, RevocationRecovery.class);
    }

    @Override
    public Revocation revoke(RevocationContext context) {
        return call("authorization/revoke", context, Revocation.class);
    }

    @Override
    public ChannelCatalogGateway.Result publish(ChannelCatalogGateway.Publication publication) {
        try {
            return call("catalog/publish", publication, ChannelCatalogGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknown(publication);
        }
    }

    @Override
    public ChannelCatalogGateway.Result query(
            ChannelCatalogGateway.Publication publication, String taskRef) {
        try {
            return call(
                    "catalog/query",
                    new MockCommercePlatform.CatalogQuery(publication, taskRef),
                    ChannelCatalogGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknown(publication);
        }
    }

    @Override
    public ChannelResourceIdentityGateway.Proof query(ChannelResourceIdentityGateway.Query query) {
        if (!profile.equals(query.profileNo()))
            throw new IllegalArgumentException("Wrong mock profile");
        return call("resources/query", query, ChannelResourceIdentityGateway.Proof.class);
    }

    @Override
    public boolean supportsDecision(
            ChannelOrderGateway.Fulfillment fulfillment, ChannelOrderGateway.Action action) {
        return fulfillment == ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY
                || fulfillment == ChannelOrderGateway.Fulfillment.PICKUP;
    }

    @Override
    public ChannelOrderGateway.Snapshot readOrder(ChannelOrderGateway.Query query) {
        if (!profile.equals(query.profileNo()))
            throw new IllegalArgumentException("Wrong mock order profile");
        return call("orders/read", query, ChannelOrderGateway.Snapshot.class);
    }

    @Override
    public ChannelOrderGateway.DecisionResult decide(ChannelOrderGateway.Decision request) {
        try {
            return call("orders/decide", request, ChannelOrderGateway.DecisionResult.class);
        } catch (MockUnavailable unknown) {
            return unknownDecision(request);
        }
    }

    @Override
    public ChannelOrderGateway.DecisionResult queryDecision(ChannelOrderGateway.Decision request) {
        try {
            return call("orders/decision-query", request, ChannelOrderGateway.DecisionResult.class);
        } catch (MockUnavailable unknown) {
            return unknownDecision(request);
        }
    }

    private ChannelOrderGateway.DecisionResult unknownDecision(
            ChannelOrderGateway.Decision request) {
        return new ChannelOrderGateway.DecisionResult(
                request.operationNo(),
                request.scope().externalOrderRef(),
                request.revisionRef(),
                request.action(),
                ChannelOrderGateway.DecisionStatus.UNKNOWN,
                null);
    }

    @Override
    public boolean supportsFulfillment(ChannelOrderGateway.Fulfillment fulfillment, Stage stage) {
        return ChannelFulfillmentPolicy.supports(fulfillment, stage);
    }

    @Override
    public PublicationResult publishFulfillment(FulfillmentPublication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock profile");
        try {
            return call("fulfillments/publish", publication, PublicationResult.class);
        } catch (MockUnavailable unknown) {
            return unknownFulfillment(publication);
        }
    }

    @Override
    public PublicationResult queryFulfillment(FulfillmentPublication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock profile");
        try {
            return call("fulfillments/query", publication, PublicationResult.class);
        } catch (MockUnavailable unknown) {
            return unknownFulfillment(publication);
        }
    }

    private PublicationResult unknownFulfillment(FulfillmentPublication publication) {
        return new PublicationResult(
                publication.operationNo(),
                publication.scope().externalOrderRef(),
                PricingSnapshotHash.of(publication),
                PublicationStatus.UNKNOWN,
                null);
    }

    @Override
    public boolean supportsAvailability(ChannelAvailabilitySourcePort.Kind kind) {
        return kind == ChannelAvailabilitySourcePort.Kind.STOCK
                || kind == ChannelAvailabilitySourcePort.Kind.STORE;
    }

    @Override
    public boolean supportsStoreMode(ChannelAvailabilitySourcePort.StoreMode mode) {
        return mode != null;
    }

    @Override
    public ChannelAvailabilityGateway.Result publishAvailability(
            ChannelAvailabilityGateway.Publication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock availability profile");
        try {
            return call(
                    "availability/publish", publication, ChannelAvailabilityGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknownAvailability(publication);
        }
    }

    @Override
    public ChannelAvailabilityGateway.Result queryAvailability(
            ChannelAvailabilityGateway.Publication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock availability profile");
        try {
            return call("availability/query", publication, ChannelAvailabilityGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknownAvailability(publication);
        }
    }

    private ChannelAvailabilityGateway.Result unknownAvailability(
            ChannelAvailabilityGateway.Publication publication) {
        return new ChannelAvailabilityGateway.Result(
                publication.operationNo(),
                PricingSnapshotHash.of(publication),
                ChannelAvailabilityGateway.Status.UNKNOWN,
                null);
    }

    @Override
    public List<ChannelAfterSaleGateway.Snapshot> readAfterSales(ChannelOrderGateway.Query scope) {
        if (!profile.equals(scope.profileNo()))
            throw new IllegalArgumentException("Wrong mock after-sale profile");
        return call("after-sales/read", scope, MockCommercePlatform.AfterSaleRead.class).cases();
    }

    @Override
    public boolean supportsAfterSale(
            ChannelAfterSaleGateway.Type type, ChannelAfterSaleGateway.Action action) {
        return type != null
                && action != null
                && (action == ChannelAfterSaleGateway.Action.REJECT
                        || type.name().equals(action.name()));
    }

    @Override
    public ChannelAfterSaleGateway.Result applyAfterSale(
            ChannelAfterSaleGateway.Publication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock after-sale profile");
        try {
            return call("after-sales/apply", publication, ChannelAfterSaleGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknownAfterSale(publication);
        }
    }

    @Override
    public ChannelAfterSaleGateway.Result queryAfterSale(
            ChannelAfterSaleGateway.Publication publication) {
        if (!profile.equals(publication.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock after-sale profile");
        try {
            return call("after-sales/query", publication, ChannelAfterSaleGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return unknownAfterSale(publication);
        }
    }

    private ChannelAfterSaleGateway.Result unknownAfterSale(ChannelAfterSaleGateway.Publication p) {
        return new ChannelAfterSaleGateway.Result(
                p.operationNo(),
                p.scope().externalOrderRef(),
                p.externalCaseRef(),
                PricingSnapshotHash.of(p),
                ChannelAfterSaleGateway.ResultStatus.UNKNOWN,
                null);
    }

    @Override
    public ChannelStatementGateway.Receipt fetchStatement(ChannelStatementGateway.Lookup lookup) {
        if (!profile.equals(lookup.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock statement profile");
        try {
            return call("statements/fetch", lookup, ChannelStatementGateway.Receipt.class);
        } catch (MockUnavailable unknown) {
            return unknownStatement(lookup);
        }
    }

    @Override
    public ChannelStatementGateway.Receipt queryStatement(ChannelStatementGateway.Lookup lookup) {
        if (!profile.equals(lookup.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock statement profile");
        try {
            return call("statements/query", lookup, ChannelStatementGateway.Receipt.class);
        } catch (MockUnavailable unknown) {
            return unknownStatement(lookup);
        }
    }

    private ChannelStatementGateway.Receipt unknownStatement(
            ChannelStatementGateway.Lookup lookup) {
        return new ChannelStatementGateway.Receipt(
                lookup.operationNo(),
                PricingSnapshotHash.of(lookup),
                ChannelStatementGateway.Status.UNKNOWN,
                null,
                null,
                null);
    }

    @Override
    public ChannelRedemptionGateway.Verified verifyCertificate(
            ChannelRedemptionGateway.Verify request) {
        if (!profile.equals(request.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock certificate profile");
        var vault = certificateVault.get();
        if (vault == null)
            throw new UnsupportedOperationException(
                    "Original encrypted certificate owner unavailable");
        return vault.withSecret(
                new ChannelCertificateHandleStore.Scope(
                        request.scope().entityNo(),
                        request.scope().storeNo(),
                        request.scope().connectionNo()),
                request.controlledHandleRef(),
                code -> {
                    String identity =
                            certificateIdentity(
                                    token, profile, request.scope().externalAccountRef(), code);
                    try {
                        return call(
                                "certificates/verify",
                                new MockCommercePlatform.CertificateQuery(request, identity),
                                ChannelRedemptionGateway.Verified.class);
                    } catch (MockUnavailable unknown) {
                        return new ChannelRedemptionGateway.Verified(
                                request.operationNo(),
                                PricingSnapshotHash.of(request),
                                ChannelRedemptionGateway.Status.UNKNOWN,
                                null);
                    }
                });
    }

    @Override
    public ChannelRedemptionGateway.Result redeemCertificate(
            ChannelRedemptionGateway.Action action) {
        return certificateAction("certificates/redeem", action);
    }

    @Override
    public ChannelRedemptionGateway.Result queryRedemption(ChannelRedemptionGateway.Action action) {
        return certificateAction("certificates/query", action);
    }

    @Override
    public ChannelRedemptionGateway.Result reverseCertificate(
            ChannelRedemptionGateway.Action action) {
        return certificateAction("certificates/reverse", action);
    }

    @Override
    public ChannelRedemptionGateway.Result queryReversal(ChannelRedemptionGateway.Action action) {
        return certificateAction("certificates/reverse-query", action);
    }

    private ChannelRedemptionGateway.Result certificateAction(
            String path, ChannelRedemptionGateway.Action action) {
        if (!profile.equals(action.scope().profileNo()))
            throw new IllegalArgumentException("Wrong mock certificate profile");
        try {
            return call(path, action, ChannelRedemptionGateway.Result.class);
        } catch (MockUnavailable unknown) {
            return new ChannelRedemptionGateway.Result(
                    action.operationNo(),
                    PricingSnapshotHash.of(action),
                    ChannelRedemptionGateway.Status.UNKNOWN,
                    action.certificateIdentityHash(),
                    null,
                    null,
                    action.unit(),
                    null);
        }
    }

    @Override
    public ChannelInboundGateway.Verified verify(Endpoint endpoint, RawMessage message) {
        if (!profile.equals(endpoint.profileNo())) throw new VerificationRejected();
        var headers = new HashMap<String, String>();
        message.headers().forEach((key, value) -> headers.put(key.toLowerCase(Locale.ROOT), value));
        String timestamp = headers.get("x-mock-timestamp"),
                credential = headers.get("x-mock-credential-ref");
        String signature = headers.get("x-mock-signature");
        if (timestamp == null
                || !timestamp.matches("[0-9]{10,12}")
                || credential == null
                || signature == null
                || !signature.matches("[a-f0-9]{64}")
                || !credential.startsWith("MOCK:")) throw new VerificationRejected();
        long seconds = Long.parseLong(timestamp), now = Instant.now().getEpochSecond();
        if (seconds < now - 300 || seconds > now + 30) throw new VerificationRejected();
        var generation =
                endpoint.credentials().stream()
                        .filter(c -> credential.equals(c.credentialRef()))
                        .findFirst()
                        .orElseThrow(VerificationRejected::new);
        String expected =
                MockCallbackSignatures.sign(token, timestamp, credential, message.bytes());
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) throw new VerificationRejected();
        var event = json.readValue(message.bytes(), MockCommercePlatform.MockEvent.class);
        if (event.eventRef() == null
                || event.eventType() == null
                || event.occurredAt() == null
                || !endpoint.externalStoreRef().equals(event.externalStoreRef()))
            throw new VerificationRejected();
        return new ChannelInboundGateway.Verified(
                event.eventRef(),
                event.eventType() + ":" + event.eventRef(),
                event.eventType(),
                event.externalStoreRef(),
                event.externalOrderRef(),
                event.externalRevision(),
                event.occurredAt(),
                generation.authorizationNo(),
                "MOCK:" + signature);
    }

    @Override
    public Ack acknowledge(ChannelInboundGateway.Verified event) {
        return new Ack(200, "application/json", "{\"received\":true}");
    }

    private ChannelCatalogGateway.Result unknown(ChannelCatalogGateway.Publication p) {
        return new ChannelCatalogGateway.Result(
                p.operationNo(),
                p.generation(),
                p.contentHash(),
                Outcome.UNKNOWN,
                false,
                p.operationNo(),
                null,
                List.of());
    }

    private <T> T call(String path, Object request, Class<T> type) {
        try {
            var message =
                    HttpRequest.newBuilder(base.resolve("/mock/v1/" + path))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            json.writeValueAsBytes(request)))
                            .build();
            var response = http.send(message, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(4 * 1024 * 1024 + 1);
                if (response.statusCode() != 200
                        || bytes.length == 0
                        || bytes.length > 4 * 1024 * 1024) throw new MockUnavailable();
                return json.readValue(bytes, type);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MockUnavailable();
        } catch (IOException | IllegalArgumentException unavailable) {
            throw new MockUnavailable();
        }
    }

    private static final class MockUnavailable extends RuntimeException {
        MockUnavailable() {
            super("Mock platform result is unavailable");
        }
    }
}
