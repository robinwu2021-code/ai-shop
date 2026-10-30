package ai.neargo.shop.commerce.channel.mock;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.commerce.policy.ChannelAfterSalePolicy;
import ai.neargo.shop.commerce.policy.ChannelFulfillmentPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.ChannelRedemptionPolicy;
import ai.neargo.shop.commerce.policy.ChannelStatementPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentPublication;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationResult;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationStatus;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A loopback-only, explicit test platform exercises real HTTP and durable external effects, never a
 * live supplier.
 */
public final class MockCommercePlatform implements AutoCloseable {
    public enum Scenario {
        SUCCESS,
        ASYNC,
        PARTIAL,
        UNKNOWN,
        PERSIST_THEN_TIMEOUT,
        REJECT
    }

    public record Fixture(
            String entityNo,
            String storeNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String identityNamespace,
            Set<String> scopes,
            Scenario scenario) {}

    private record StoredAuthorization(
            ChannelAuthorizationGateway.AuthorizationContext context, Instant expiresAt) {}

    private record StoredRevocation(
            ChannelAuthorizationGateway.RevocationContext context,
            ChannelAuthorizationGateway.Revocation result) {}

    public record Exchange(ChannelAuthorizationGateway.AuthorizationContext context, String code) {}

    public record MockEvent(
            String eventRef,
            String eventType,
            String externalStoreRef,
            String externalOrderRef,
            String externalRevision,
            Instant occurredAt) {}

    public record Emission(
            String callbackUrl,
            String credentialRef,
            MockEvent event,
            int copies,
            boolean corruptSignature) {}

    public record OrderSeed(
            String profileNo, ChannelOrderGateway.Document document, Scenario scenario) {}

    public record ResolveFulfillment(
            FulfillmentPublication publication, PublicationStatus status) {}

    private record StoredFulfillment(
            String requestHash,
            FulfillmentPublication publication,
            PublicationResult result,
            int queries,
            Scenario scenario) {}

    private record FulfillmentBook(
            List<StoredFulfillment> actions, List<FulfillmentPublication> effects) {
        FulfillmentBook {
            actions = List.copyOf(actions);
            effects = List.copyOf(effects);
        }
    }

    public record AfterSaleSeed(
            String profileNo, ChannelAfterSaleGateway.Document document, Scenario scenario) {}

    public record AfterSaleRead(List<ChannelAfterSaleGateway.Snapshot> cases) {
        public AfterSaleRead {
            cases = List.copyOf(cases);
        }
    }

    public record ResolveAfterSale(
            ChannelAfterSaleGateway.Publication publication,
            ChannelAfterSaleGateway.ResultStatus status) {}

    private record StoredAfterSale(
            String requestHash,
            ChannelAfterSaleGateway.Publication publication,
            ChannelAfterSaleGateway.Result result,
            int queries,
            Scenario scenario) {}

    private record AfterSaleBook(
            List<AfterSaleSeed> cases,
            List<StoredAfterSale> actions,
            List<ChannelAfterSaleGateway.Publication> effects) {
        public AfterSaleBook {
            cases = List.copyOf(cases);
            actions = List.copyOf(actions);
            effects = List.copyOf(effects);
        }
    }

    public record CertificateQuery(
            ChannelRedemptionGateway.Verify request, String certificateFingerprint) {}

    public record CertificateSeed(
            String entityNo,
            String profileNo,
            String externalAccountRef,
            Set<String> eligibleExternalStores,
            ChannelRedemptionGateway.Certificate certificate,
            Scenario scenario) {}

    public record ResolveCertificate(
            ChannelRedemptionGateway.Action action, ChannelRedemptionGateway.Status status) {}

    private record StoredCertificateVerification(
            String requestHash,
            CertificateQuery query,
            ChannelRedemptionGateway.Verified result,
            int queries) {}

    private record CertificateAction(
            String requestHash,
            ChannelRedemptionGateway.Action action,
            ChannelRedemptionGateway.Result result,
            int queries,
            Scenario scenario) {}

    private record CertificateBook(
            CertificateSeed seed,
            BigDecimal consumedQuantity,
            BigDecimal reversedQuantity,
            List<CertificateAction> actions) {
        CertificateBook {
            actions = List.copyOf(actions);
        }
    }

    public record StatementSeed(
            String entityNo, ChannelStatementGateway.Document document, Scenario scenario) {}

    public record ResolveStatementRead(String operationNo, ChannelStatementGateway.Status status) {}

    private record StoredStatementRead(
            String lookupHash,
            ChannelStatementGateway.Lookup lookup,
            ChannelStatementGateway.Receipt receipt,
            ChannelStatementGateway.Document originalDocument,
            int queries,
            Scenario scenario) {}

    public record ResolveAvailability(
            ChannelAvailabilityGateway.Publication publication,
            ChannelAvailabilityGateway.Status status) {}

    private record StoredAvailability(
            String requestHash,
            ChannelAvailabilityGateway.Publication publication,
            ChannelAvailabilityGateway.Result result,
            int queries,
            Scenario scenario) {}

    private record AvailabilityBook(
            long generation,
            List<StoredAvailability> actions,
            ChannelAvailabilityGateway.Publication effect) {
        AvailabilityBook {
            actions = List.copyOf(actions);
        }
    }

    public record ResolveDecision(String operationNo, ChannelOrderGateway.DecisionStatus status) {}

    private record StoredDecision(
            String requestHash,
            ChannelOrderGateway.Decision request,
            ChannelOrderGateway.DecisionResult result,
            int queries,
            Scenario scenario) {}

    public record Resolve(String operationNo, ChannelCatalogGateway.Outcome outcome) {}

    public record CatalogQuery(ChannelCatalogGateway.Publication publication, String taskRef) {}

    public record RemoteObject(
            ChannelResourceIdentityGateway.ObjectType type,
            String key,
            String sourceRef,
            ChannelResourceIdentityGateway.Identity identity) {}

    private record StoredCatalog(
            String operationNo, long generation, String contentHash, List<RemoteObject> objects) {}

    private record StoredOperation(
            String requestHash,
            ChannelCatalogGateway.Publication publication,
            ChannelCatalogGateway.Result result,
            int queries,
            Scenario scenario) {}

    private final Set<String> crashAfterRecord = ConcurrentHashMap.newKeySet();

    /**
     * Non-production fault injection stops between the durable original action and its local
     * projection.
     */
    public void failAfterCommitOnce(String operationNo) {
        crashAfterRecord.add(operationNo);
    }

    private void afterRecord(String operationNo) {
        if (crashAfterRecord.remove(operationNo))
            throw new IllegalStateException("Mock crash after original record");
    }

    private final ObjectMapper json;
    private final Path directory;
    private final byte[] token;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Fixture and operation files survive mock restart so UNKNOWN recovery can be tested across
     * process boundaries.
     */
    public MockCommercePlatform(ObjectMapper json, Path directory, String token, int port)
            throws IOException {
        if (token == null || token.length() < 32 || port < 0 || port > 65535)
            throw new IllegalArgumentException("Mock config");
        this.json = json;
        this.directory = directory.toAbsolutePath().normalize();
        this.token = token.getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(this.directory);
        this.server =
                HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32);
        server.createContext("/mock/v1/", this::handle);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String given = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"POST".equals(exchange.getRequestMethod())
                    || given == null
                    || !MessageDigest.isEqual(
                            ("Bearer " + new String(token, StandardCharsets.UTF_8))
                                    .getBytes(StandardCharsets.UTF_8),
                            given.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, null);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
            if (body.length > 4 * 1024 * 1024) {
                respond(exchange, 413, null);
                return;
            }
            Object result;
            synchronized (this) {
                result = dispatch(exchange.getRequestURI().getPath(), body);
            }
            if (result == Timeout.INSTANCE) {
                respond(exchange, 503, null);
                return;
            }
            respond(exchange, 200, result);
        } catch (IllegalArgumentException bad) {
            respond(exchange, 400, null);
        } catch (RuntimeException failure) {
            respond(exchange, 503, null);
        } finally {
            exchange.close();
        }
    }

    private enum Timeout {
        INSTANCE
    }

    private Object dispatch(String path, byte[] body) {
        return switch (path) {
            case "/mock/v1/admin/after-sales" ->
                    seedAfterSale(json.readValue(body, AfterSaleSeed.class));
            case "/mock/v1/admin/after-sales/resolve" ->
                    resolveAfterSale(json.readValue(body, ResolveAfterSale.class));
            case "/mock/v1/after-sales/read" ->
                    readAfterSales(json.readValue(body, ChannelOrderGateway.Query.class));
            case "/mock/v1/after-sales/apply" ->
                    applyAfterSale(json.readValue(body, ChannelAfterSaleGateway.Publication.class));
            case "/mock/v1/after-sales/query" ->
                    queryAfterSale(json.readValue(body, ChannelAfterSaleGateway.Publication.class));

            case "/mock/v1/admin/statements" ->
                    seedStatement(json.readValue(body, StatementSeed.class));
            case "/mock/v1/admin/statements/resolve" ->
                    resolveStatementRead(json.readValue(body, ResolveStatementRead.class));
            case "/mock/v1/admin/certificates" ->
                    seedCertificate(json.readValue(body, CertificateSeed.class));
            case "/mock/v1/admin/certificates/resolve" ->
                    resolveCertificate(json.readValue(body, ResolveCertificate.class));
            case "/mock/v1/certificates/verify" ->
                    verifyCertificate(json.readValue(body, CertificateQuery.class));
            case "/mock/v1/certificates/redeem" ->
                    redeemCertificate(
                            json.readValue(body, ChannelRedemptionGateway.Action.class), false);
            case "/mock/v1/certificates/query" ->
                    queryCertificate(
                            json.readValue(body, ChannelRedemptionGateway.Action.class), false);
            case "/mock/v1/certificates/reverse" ->
                    redeemCertificate(
                            json.readValue(body, ChannelRedemptionGateway.Action.class), true);
            case "/mock/v1/certificates/reverse-query" ->
                    queryCertificate(
                            json.readValue(body, ChannelRedemptionGateway.Action.class), true);
            case "/mock/v1/statements/fetch" ->
                    fetchStatement(json.readValue(body, ChannelStatementGateway.Lookup.class));
            case "/mock/v1/statements/query" ->
                    queryStatement(json.readValue(body, ChannelStatementGateway.Lookup.class));
            case "/mock/v1/availability/publish" ->
                    publishAvailability(
                            json.readValue(body, ChannelAvailabilityGateway.Publication.class));
            case "/mock/v1/availability/query" ->
                    queryAvailability(
                            json.readValue(body, ChannelAvailabilityGateway.Publication.class));
            case "/mock/v1/admin/availability/resolve" ->
                    resolveAvailability(json.readValue(body, ResolveAvailability.class));
            case "/mock/v1/fulfillments/publish" ->
                    publishFulfillment(json.readValue(body, FulfillmentPublication.class));
            case "/mock/v1/fulfillments/query" ->
                    queryFulfillment(json.readValue(body, FulfillmentPublication.class));
            case "/mock/v1/admin/fulfillments/resolve" ->
                    resolveFulfillment(json.readValue(body, ResolveFulfillment.class));
            case "/mock/v1/admin/orders" -> seedOrder(json.readValue(body, OrderSeed.class));
            case "/mock/v1/admin/order-decisions/resolve" ->
                    resolveDecision(json.readValue(body, ResolveDecision.class));
            case "/mock/v1/orders/read" ->
                    readOrder(json.readValue(body, ChannelOrderGateway.Query.class));
            case "/mock/v1/orders/decide" ->
                    decide(json.readValue(body, ChannelOrderGateway.Decision.class));
            case "/mock/v1/orders/decision-query" ->
                    queryDecision(json.readValue(body, ChannelOrderGateway.Decision.class));
            case "/mock/v1/admin/fixtures" -> fixture(json.readValue(body, Fixture.class));
            case "/mock/v1/admin/operations/resolve" ->
                    resolve(json.readValue(body, Resolve.class));
            case "/mock/v1/admin/events/emit" -> emit(json.readValue(body, Emission.class));
            case "/mock/v1/identity/verify" ->
                    verify(json.readValue(body, ChannelConnectionIdentityPort.Identity.class));
            case "/mock/v1/authorization/query" ->
                    queryAuthorization(
                            json.readValue(
                                    body, ChannelAuthorizationGateway.AuthorizationQuery.class));
            case "/mock/v1/authorization/revocation-query" ->
                    queryRevocation(
                            json.readValue(
                                    body, ChannelAuthorizationGateway.RevocationContext.class));
            case "/mock/v1/authorization/begin" ->
                    begin(
                            json.readValue(
                                    body, ChannelAuthorizationGateway.AuthorizationContext.class));
            case "/mock/v1/authorization/exchange" ->
                    exchange(json.readValue(body, Exchange.class));
            case "/mock/v1/authorization/revoke" ->
                    revoke(
                            json.readValue(
                                    body, ChannelAuthorizationGateway.RevocationContext.class));
            case "/mock/v1/catalog/publish" ->
                    publish(json.readValue(body, ChannelCatalogGateway.Publication.class));
            case "/mock/v1/catalog/query" -> query(json.readValue(body, CatalogQuery.class));
            case "/mock/v1/resources/query" ->
                    resource(json.readValue(body, ChannelResourceIdentityGateway.Query.class));
            default -> throw new IllegalArgumentException("Unknown mock route");
        };
    }

    private OrderSeed seedOrder(OrderSeed seed) {
        var d = seed.document();
        var f =
                fixtures().stream()
                        .filter(
                                fixture ->
                                        fixture.profileNo().equals(seed.profileNo())
                                                && fixture.externalAccountRef()
                                                        .equals(d.externalAccountRef())
                                                && fixture.externalStoreRef()
                                                        .equals(d.externalStoreRef()))
                        .findFirst()
                        .orElseThrow();
        require(seed.scenario() != null);
        var query =
                new ChannelOrderGateway.Query(
                        f.entityNo(),
                        f.storeNo(),
                        "SEED",
                        f.profileNo(),
                        d.externalAccountRef(),
                        d.externalStoreRef(),
                        d.externalOrderRef(),
                        d.revisionRef(),
                        null,
                        null);
        ChannelOrderPolicy.validate(query, orderSnapshot(d), Clock.systemUTC());
        String key = orderKey(query);
        var existing = load("order-revision", key + ":" + d.revisionRef(), OrderSeed.class);
        require(existing == null || existing.document().equals(d));
        save("order-revision", key + ":" + d.revisionRef(), seed);
        var current = load("order", key, OrderSeed.class);
        if (current == null
                || current.document().revisionRef().equals(d.revisionRef())
                || d.revisionSequence() != null
                        && current.document().revisionSequence() != null
                        && d.revisionSequence() > current.document().revisionSequence())
            save("order", key, seed);
        return seed;
    }

    private ChannelOrderGateway.Snapshot orderSnapshot(ChannelOrderGateway.Document d) {
        var now = Instant.now();
        return new ChannelOrderGateway.Snapshot(
                d,
                now,
                now.plusSeconds(30),
                "MOCK:ORDER:" + PricingSnapshotHash.of(d),
                PricingSnapshotHash.of(d));
    }

    private static String orderKey(ChannelOrderGateway.Query q) {
        return PricingSnapshotHash.of(
                List.of(
                        q.profileNo(),
                        q.externalAccountRef(),
                        q.externalStoreRef(),
                        q.externalOrderRef()));
    }

    private void orderScope(ChannelOrderGateway.Query q) {
        orderScope(q, "ORDER");
    }

    private void orderScope(ChannelOrderGateway.Query q, String requiredScope) {
        var f = fixture(q.profileNo(), q.externalAccountRef(), q.externalStoreRef(), q.entityNo());
        require(f.storeNo().equals(q.storeNo()));
        var grant = load("grant", q.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(q.externalAccountRef())
                        && grant.grantedScopes().contains(requiredScope)
                        && grant.expiresAt().isAfter(Instant.now())
                        && !Boolean.TRUE.equals(load("revoked", q.credentialRef(), Boolean.class)));
    }

    private ChannelOrderGateway.Snapshot readOrder(ChannelOrderGateway.Query query) {
        orderScope(query);
        var seed =
                load(
                        query.externalRevision() == null ? "order" : "order-revision",
                        orderKey(query)
                                + (query.externalRevision() == null
                                        ? ""
                                        : ":" + query.externalRevision()),
                        OrderSeed.class);
        require(seed != null);
        return orderSnapshot(seed.document());
    }

    private Object decide(ChannelOrderGateway.Decision request) {
        orderScope(request.scope());
        var previous = load("order-decision", request.operationNo(), StoredDecision.class);
        if (previous != null) {
            require(previous.requestHash().equals(PricingSnapshotHash.of(request)));
            if (previous.result().status() == ChannelOrderGateway.DecisionStatus.CONFIRMED)
                applyDecision(request);
            return previous.result();
        }
        var seed = load("order", orderKey(request.scope()), OrderSeed.class);
        require(
                seed != null
                        && request.action() != null
                        && request.documentHash().equals(PricingSnapshotHash.of(seed.document()))
                        && request.revisionRef().equals(seed.document().revisionRef()));
        if (request.action() == ChannelOrderGateway.Action.REJECT)
            require(request.reasonCode() != null);
        boolean terminal =
                seed.scenario() == Scenario.SUCCESS
                        || seed.scenario() == Scenario.REJECT
                        || seed.scenario() == Scenario.PERSIST_THEN_TIMEOUT;
        var status =
                !terminal
                        ? ChannelOrderGateway.DecisionStatus.UNKNOWN
                        : seed.scenario() == Scenario.REJECT
                                ? ChannelOrderGateway.DecisionStatus.REJECTED
                                : ChannelOrderGateway.DecisionStatus.CONFIRMED;
        var result = decisionResult(request, status);
        save(
                "order-decision",
                request.operationNo(),
                new StoredDecision(
                        PricingSnapshotHash.of(request), request, result, 0, seed.scenario()));
        afterRecord(request.operationNo());
        if (status == ChannelOrderGateway.DecisionStatus.CONFIRMED) applyDecision(request);
        return seed.scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private ChannelOrderGateway.DecisionResult queryDecision(ChannelOrderGateway.Decision request) {
        orderScope(request.scope());
        var previous = load("order-decision", request.operationNo(), StoredDecision.class);
        if (previous == null)
            return decisionResult(request, ChannelOrderGateway.DecisionStatus.UNKNOWN);
        require(previous.requestHash().equals(PricingSnapshotHash.of(request)));
        var result = previous.result();
        if (previous.scenario() == Scenario.ASYNC && previous.queries() >= 1) {
            result = decisionResult(request, ChannelOrderGateway.DecisionStatus.CONFIRMED);
        }
        save(
                "order-decision",
                request.operationNo(),
                new StoredDecision(
                        previous.requestHash(),
                        request,
                        result,
                        previous.queries() + 1,
                        previous.scenario()));
        if (result.status() == ChannelOrderGateway.DecisionStatus.CONFIRMED) applyDecision(request);
        return result;
    }

    private ChannelOrderGateway.DecisionResult resolveDecision(ResolveDecision resolution) {
        require(
                resolution.status() != null
                        && resolution.status() != ChannelOrderGateway.DecisionStatus.UNKNOWN);
        var previous = load("order-decision", resolution.operationNo(), StoredDecision.class);
        require(
                previous != null
                        && (previous.result().status() == ChannelOrderGateway.DecisionStatus.UNKNOWN
                                || previous.result().status() == resolution.status()));
        var result = decisionResult(previous.request(), resolution.status());
        save(
                "order-decision",
                resolution.operationNo(),
                new StoredDecision(
                        previous.requestHash(),
                        previous.request(),
                        result,
                        previous.queries(),
                        Scenario.SUCCESS));
        if (result.status() == ChannelOrderGateway.DecisionStatus.CONFIRMED)
            applyDecision(previous.request());
        return result;
    }

    private ChannelOrderGateway.DecisionResult decisionResult(
            ChannelOrderGateway.Decision request, ChannelOrderGateway.DecisionStatus status) {
        return new ChannelOrderGateway.DecisionResult(
                request.operationNo(),
                request.scope().externalOrderRef(),
                request.revisionRef(),
                request.action(),
                status,
                status == ChannelOrderGateway.DecisionStatus.UNKNOWN
                        ? null
                        : "MOCK:DECISION:" + PricingSnapshotHash.of(request));
    }

    private void applyDecision(ChannelOrderGateway.Decision request) {
        var current = load("order", orderKey(request.scope()), OrderSeed.class);
        if (!request.revisionRef().equals(current.document().revisionRef())) return;
        var d = current.document();
        var state =
                request.action() == ChannelOrderGateway.Action.ACCEPT
                        ? ChannelOrderGateway.State.ACCEPTED
                        : ChannelOrderGateway.State.CANCELLED;
        var changed =
                new ChannelOrderGateway.Document(
                        d.schemaVersion(),
                        d.externalAccountRef(),
                        d.externalStoreRef(),
                        d.externalOrderRef(),
                        d.revisionRef() + ":" + request.action(),
                        d.revisionSequence() == null
                                ? null
                                : Math.addExact(d.revisionSequence(), 1),
                        d.catalogHash(),
                        d.currency(),
                        state,
                        d.paymentState(),
                        d.originalPaymentRef(),
                        d.fulfillment(),
                        d.orderedAt(),
                        d.requestedAt(),
                        d.customer(),
                        d.lines(),
                        d.totals());
        seedOrder(new OrderSeed(request.scope().profileNo(), changed, current.scenario()));
    }

    private AfterSaleBook afterSaleBook(ChannelOrderGateway.Query scope) {
        var book = load("after-sale-book", orderKey(scope), AfterSaleBook.class);
        return book == null ? new AfterSaleBook(List.of(), List.of(), List.of()) : book;
    }

    private ChannelAfterSaleGateway.Snapshot afterSaleSnapshot(ChannelAfterSaleGateway.Document d) {
        var now = Instant.now();
        return new ChannelAfterSaleGateway.Snapshot(
                d,
                PricingSnapshotHash.of(d),
                now,
                now.plusSeconds(30),
                "MOCK:AFTER_SALE_READ:" + PricingSnapshotHash.of(d));
    }

    private AfterSaleSeed seedAfterSale(AfterSaleSeed seed) {
        require(seed != null && seed.scenario() != null && seed.document() != null);
        var d = seed.document();
        var fixtures =
                fixtures().stream()
                        .filter(
                                f ->
                                        f.profileNo().equals(seed.profileNo())
                                                && f.externalAccountRef()
                                                        .equals(d.externalAccountRef())
                                                && f.externalStoreRef()
                                                        .equals(d.externalStoreRef()))
                        .toList();
        require(fixtures.size() == 1);
        var f = fixtures.getFirst();
        var q =
                new ChannelOrderGateway.Query(
                        f.entityNo(),
                        f.storeNo(),
                        "SEED",
                        f.profileNo(),
                        d.externalAccountRef(),
                        d.externalStoreRef(),
                        d.externalOrderRef(),
                        null,
                        null,
                        null);
        ChannelAfterSalePolicy.snapshot(q, afterSaleSnapshot(d), Clock.systemUTC());
        var order = load("order", orderKey(q), OrderSeed.class);
        require(order != null && d.currency().equals(order.document().currency()));
        require(d.merchantRefundMinor() <= order.document().totals().merchantReceivableMinor());
        var book = afterSaleBook(q);
        var prior =
                book.cases().stream()
                        .filter(
                                c ->
                                        c.document().externalCaseRef().equals(d.externalCaseRef())
                                                && c.document()
                                                        .revisionRef()
                                                        .equals(d.revisionRef()))
                        .findFirst()
                        .orElse(null);
        require(prior == null || prior.document().equals(d));
        var values = new ArrayList<>(book.cases());
        if (prior == null) values.add(seed);
        require(values.size() <= 5000);
        save(
                "after-sale-book",
                orderKey(q),
                new AfterSaleBook(values, book.actions(), book.effects()));
        return seed;
    }

    private AfterSaleRead readAfterSales(ChannelOrderGateway.Query scope) {
        orderScope(scope, "AFTER_SALE");
        var current = new HashMap<String, AfterSaleSeed>();
        for (var seed : afterSaleBook(scope).cases()) {
            var prior = current.get(seed.document().externalCaseRef());
            if (prior == null || seed.document().sequence() > prior.document().sequence())
                current.put(seed.document().externalCaseRef(), seed);
        }
        require(current.size() <= 100);
        return new AfterSaleRead(
                current.values().stream()
                        .sorted(java.util.Comparator.comparing(c -> c.document().externalCaseRef()))
                        .map(c -> afterSaleSnapshot(c.document()))
                        .toList());
    }

    private StoredAfterSale afterSaleAction(
            AfterSaleBook book, ChannelAfterSaleGateway.Publication p) {
        var action =
                book.actions().stream()
                        .filter(a -> a.publication().operationNo().equals(p.operationNo()))
                        .findFirst()
                        .orElse(null);
        if (action != null) require(action.requestHash().equals(PricingSnapshotHash.of(p)));
        return action;
    }

    private ChannelAfterSaleGateway.Result afterSaleResult(
            ChannelAfterSaleGateway.Publication p, ChannelAfterSaleGateway.ResultStatus status) {
        return new ChannelAfterSaleGateway.Result(
                p.operationNo(),
                p.scope().externalOrderRef(),
                p.externalCaseRef(),
                PricingSnapshotHash.of(p),
                status,
                status == ChannelAfterSaleGateway.ResultStatus.UNKNOWN
                        ? null
                        : "MOCK:AFTER_SALE:" + PricingSnapshotHash.of(p));
    }

    private AfterSaleSeed afterSaleOriginal(
            AfterSaleBook book, ChannelAfterSaleGateway.Publication p) {
        require(
                p != null
                        && p.scope() != null
                        && p.action() != null
                        && p.sourceDecisionRef() != null
                        && p.sourceRevision() != null
                        && p.sourceDecisionHash() != null
                        && p.sourceDecisionHash().matches("[a-f0-9]{64}"));
        var original =
                book.cases().stream()
                        .filter(
                                c ->
                                        c.document().externalCaseRef().equals(p.externalCaseRef())
                                                && c.document()
                                                        .revisionRef()
                                                        .equals(p.caseRevision())
                                                && PricingSnapshotHash.of(c.document())
                                                        .equals(p.caseHash()))
                        .findFirst()
                        .orElseThrow();
        var historicalOrder =
                load(
                        "order-revision",
                        orderKey(p.scope()) + ":" + p.scope().externalRevision(),
                        OrderSeed.class);
        require(
                historicalOrder != null
                        && PricingSnapshotHash.of(historicalOrder.document())
                                .equals(p.originalOrderHash()));
        require(p.currency().equals(original.document().currency()));
        if (p.action() != ChannelAfterSaleGateway.Action.REJECT) {
            require(
                    p.action().name().equals(original.document().type().name())
                            && p.fundsAuthorizationRef() != null
                            && !p.fundsAuthorizationRef().isBlank()
                            && p.merchantRefundMinor() == original.document().merchantRefundMinor()
                            && p.lines().size() == original.document().lines().size());
            for (var line : p.lines())
                require(
                        original.document().lines().stream()
                                .anyMatch(
                                        l ->
                                                l.externalLineRef().equals(line.externalLineRef())
                                                        && l.unit().equals(line.unit())
                                                        && new BigDecimal(l.quantity())
                                                                        .compareTo(
                                                                                new BigDecimal(
                                                                                        line
                                                                                                .quantity()))
                                                                == 0
                                                        && l.merchantRefundMinor()
                                                                == line.merchantRefundMinor()));
        } else require(p.merchantRefundMinor() == 0 && p.lines().isEmpty());
        return original;
    }

    private Object applyAfterSale(ChannelAfterSaleGateway.Publication p) {
        orderScope(p.scope(), "AFTER_SALE");
        var currentOriginal = load("order", orderKey(p.scope()), OrderSeed.class);
        require(
                currentOriginal != null
                        && Set.of(
                                        ChannelOrderGateway.State.ACCEPTED,
                                        ChannelOrderGateway.State.COMPLETED)
                                .contains(currentOriginal.document().state()));
        var book = afterSaleBook(p.scope());
        var original = afterSaleOriginal(book, p);
        var previous = afterSaleAction(book, p);
        if (previous != null) return previous.result();
        var latest =
                book.cases().stream()
                        .filter(c -> c.document().externalCaseRef().equals(p.externalCaseRef()))
                        .max(java.util.Comparator.comparing(c -> c.document().sequence()))
                        .orElseThrow();
        require(
                PricingSnapshotHash.of(latest.document()).equals(p.caseHash())
                        && Set.of(
                                        ChannelAfterSaleGateway.State.REQUESTED,
                                        ChannelAfterSaleGateway.State.RETURN_IN_PROGRESS)
                                .contains(latest.document().state()));
        var status =
                switch (original.scenario()) {
                    case ASYNC, UNKNOWN -> ChannelAfterSaleGateway.ResultStatus.UNKNOWN;
                    case REJECT -> ChannelAfterSaleGateway.ResultStatus.REJECTED;
                    default -> ChannelAfterSaleGateway.ResultStatus.ACKNOWLEDGED;
                };
        var result = persistAfterSale(book, p, status, 0, original.scenario());
        afterRecord(p.operationNo());
        return original.scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private ChannelAfterSaleGateway.Result queryAfterSale(ChannelAfterSaleGateway.Publication p) {
        // Query uses the original operation after grant expiry/revoke; it never sends or creates a
        // new effect.
        fixture(
                p.scope().profileNo(),
                p.scope().externalAccountRef(),
                p.scope().externalStoreRef(),
                p.scope().entityNo());
        var book = afterSaleBook(p.scope());
        var prior = afterSaleAction(book, p);
        if (prior == null) return afterSaleResult(p, ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
        var status = prior.result().status();
        if (status == ChannelAfterSaleGateway.ResultStatus.UNKNOWN
                && prior.scenario() == Scenario.ASYNC
                && prior.queries() >= 1) status = ChannelAfterSaleGateway.ResultStatus.ACKNOWLEDGED;
        return persistAfterSale(book, p, status, prior.queries() + 1, prior.scenario());
    }

    private ChannelAfterSaleGateway.Result resolveAfterSale(ResolveAfterSale request) {
        require(
                request != null
                        && request.status() != null
                        && request.status() != ChannelAfterSaleGateway.ResultStatus.UNKNOWN);
        var p = request.publication();
        var book = afterSaleBook(p.scope());
        var prior = afterSaleAction(book, p);
        require(prior != null);
        require(
                prior.result().status() == ChannelAfterSaleGateway.ResultStatus.UNKNOWN
                        || prior.result().status() == request.status());
        require(
                request.status() != ChannelAfterSaleGateway.ResultStatus.REFUNDED
                        || p.action() != ChannelAfterSaleGateway.Action.REJECT);
        return persistAfterSale(book, p, request.status(), prior.queries(), prior.scenario());
    }

    private ChannelAfterSaleGateway.Result persistAfterSale(
            AfterSaleBook book,
            ChannelAfterSaleGateway.Publication p,
            ChannelAfterSaleGateway.ResultStatus status,
            int queries,
            Scenario scenario) {
        var effects = new ArrayList<>(book.effects());
        var previous = afterSaleAction(book, p);
        boolean success =
                Set.of(
                                ChannelAfterSaleGateway.ResultStatus.ACKNOWLEDGED,
                                ChannelAfterSaleGateway.ResultStatus.REFUNDED)
                        .contains(status);
        boolean already = effects.stream().anyMatch(e -> e.operationNo().equals(p.operationNo()));
        if (success && !already) {
            boolean reusedCase =
                    effects.stream().anyMatch(e -> e.externalCaseRef().equals(p.externalCaseRef()));
            var order =
                    load(
                            "order-revision",
                            orderKey(p.scope()) + ":" + p.scope().externalRevision(),
                            OrderSeed.class);
            require(order != null);
            long total = p.merchantRefundMinor();
            for (var effect : effects) total = Math.addExact(total, effect.merchantRefundMinor());
            boolean capped = total > order.document().totals().merchantReceivableMinor();
            for (var selected : p.lines()) {
                var originalLine =
                        order.document().lines().stream()
                                .filter(l -> l.lineRef().equals(selected.externalLineRef()))
                                .findFirst()
                                .orElse(null);
                if (originalLine == null) {
                    capped = true;
                    continue;
                }
                var quantity = new BigDecimal(selected.quantity());
                long amount = selected.merchantRefundMinor();
                for (var effect : effects)
                    for (var line : effect.lines())
                        if (line.externalLineRef().equals(selected.externalLineRef())) {
                            quantity = quantity.add(new BigDecimal(line.quantity()));
                            amount = Math.addExact(amount, line.merchantRefundMinor());
                        }
                if (!originalLine.unit().equals(selected.unit())
                        || quantity.compareTo(new BigDecimal(originalLine.quantity())) > 0
                        || amount > originalLine.netMinor()) capped = true;
            }
            if (reusedCase || capped) status = ChannelAfterSaleGateway.ResultStatus.REJECTED;
            else effects.add(p);
        }
        var result = afterSaleResult(p, status);
        var actions = new ArrayList<>(book.actions());
        actions.removeIf(a -> a.publication().operationNo().equals(p.operationNo()));
        actions.add(new StoredAfterSale(PricingSnapshotHash.of(p), p, result, queries, scenario));
        require(actions.size() <= 5000);
        // Original decision receipt and mock supplier effects commit atomically, without touching
        // local treasury or inventory.
        save(
                "after-sale-book",
                orderKey(p.scope()),
                new AfterSaleBook(book.cases(), actions, effects));
        return result;
    }

    private String certificateKey(String profile, String account, String identity) {
        return PricingSnapshotHash.of(List.of(profile, account, identity));
    }

    private CertificateBook certificateBook(ChannelRedemptionGateway.Scope scope, String identity) {
        var book =
                load(
                        "certificate-book",
                        certificateKey(scope.profileNo(), scope.externalAccountRef(), identity),
                        CertificateBook.class);
        require(
                book != null
                        && scope.entityNo().equals(book.seed().entityNo())
                        && book.seed().eligibleExternalStores().contains(scope.externalStoreRef()));
        return book;
    }

    private void certificateScope(ChannelRedemptionGateway.Scope scope) {
        var f =
                fixture(
                        scope.profileNo(),
                        scope.externalAccountRef(),
                        scope.externalStoreRef(),
                        scope.entityNo());
        require(f.storeNo().equals(scope.storeNo()));
        var grant = load("grant", scope.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(scope.externalAccountRef())
                        && grant.grantedScopes().contains("REDEMPTION")
                        && grant.expiresAt().isAfter(Instant.now())
                        && !Boolean.TRUE.equals(
                                load("revoked", scope.credentialRef(), Boolean.class)));
    }

    private CertificateSeed seedCertificate(CertificateSeed seed) {
        require(
                seed != null
                        && seed.scenario() != null
                        && seed.eligibleExternalStores() != null
                        && !seed.eligibleExternalStores().isEmpty());
        var c = seed.certificate();
        require(c != null);
        ChannelRedemptionPolicy.hash(c.certificateIdentityHash());
        ChannelRedemptionPolicy.quantity(c.totalQuantity(), false);
        ChannelRedemptionPolicy.quantity(c.consumedQuantity(), true);
        ChannelRedemptionPolicy.quantity(c.remainingQuantity(), true);
        require(
                c.totalQuantity().compareTo(c.consumedQuantity().add(c.remainingQuantity())) == 0
                        && c.unit() != null
                        && c.productIdentity() != null
                        && c.expiresAt() != null);
        for (var store : seed.eligibleExternalStores())
            fixture(seed.profileNo(), seed.externalAccountRef(), store, seed.entityNo());
        String key =
                certificateKey(
                        seed.profileNo(), seed.externalAccountRef(), c.certificateIdentityHash());
        var existing = load("certificate-book", key, CertificateBook.class);
        if (existing != null)
            require(
                    existing.seed().entityNo().equals(seed.entityNo())
                            && existing.seed().certificate().equals(c)
                            && existing.seed()
                                    .eligibleExternalStores()
                                    .equals(seed.eligibleExternalStores()));
        save(
                "certificate-book",
                key,
                new CertificateBook(
                        seed,
                        existing == null ? c.consumedQuantity() : existing.consumedQuantity(),
                        existing == null ? BigDecimal.ZERO : existing.reversedQuantity(),
                        existing == null ? List.of() : existing.actions()));
        return seed;
    }

    private ChannelRedemptionGateway.Certificate certificateSnapshot(CertificateBook book) {
        var c = book.seed().certificate();
        var now = Instant.now();
        var valid =
                now.plusSeconds(30).isBefore(c.expiresAt()) ? now.plusSeconds(30) : c.expiresAt();
        return new ChannelRedemptionGateway.Certificate(
                c.certificateIdentityHash(),
                c.productIdentity(),
                c.externalOrderRef(),
                c.totalQuantity(),
                book.consumedQuantity(),
                c.totalQuantity().subtract(book.consumedQuantity()),
                c.unit(),
                c.expiresAt(),
                valid,
                "MOCK:CERTIFICATE:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        c.certificateIdentityHash(),
                                        book.consumedQuantity(),
                                        valid)));
    }

    private ChannelRedemptionGateway.Verified verifyCertificate(CertificateQuery query) {
        require(query != null && query.request() != null);
        var request = query.request();
        certificateScope(request.scope());
        var existing =
                load(
                        "certificate-verification",
                        request.operationNo(),
                        StoredCertificateVerification.class);
        if (existing != null) {
            require(
                    existing.requestHash().equals(PricingSnapshotHash.of(request))
                            && existing.query()
                                    .certificateFingerprint()
                                    .equals(query.certificateFingerprint()));
            if (existing.result().status() != ChannelRedemptionGateway.Status.UNKNOWN)
                return existing.result();
        }
        var book = certificateBook(request.scope(), query.certificateFingerprint());
        var c = book.seed().certificate();
        var catalog =
                load("catalog", request.scope().connectionNo() + ":STORE", StoredCatalog.class);
        require(
                catalog != null
                        && catalog.objects().stream()
                                .anyMatch(
                                        o ->
                                                o.type()
                                                                == ChannelResourceIdentityGateway
                                                                        .ObjectType.SKU
                                                        && o.identity()
                                                                .equals(c.productIdentity())));
        int queries = existing == null ? 0 : existing.queries() + 1;
        var status =
                !c.expiresAt().isAfter(Instant.now()) || book.seed().scenario() == Scenario.REJECT
                        ? ChannelRedemptionGateway.Status.REJECTED
                        : book.seed().scenario() == Scenario.UNKNOWN
                                        || book.seed().scenario() == Scenario.ASYNC && queries < 1
                                ? ChannelRedemptionGateway.Status.UNKNOWN
                                : ChannelRedemptionGateway.Status.CONFIRMED;
        var result =
                new ChannelRedemptionGateway.Verified(
                        request.operationNo(),
                        PricingSnapshotHash.of(request),
                        status,
                        status == ChannelRedemptionGateway.Status.CONFIRMED
                                ? certificateSnapshot(book)
                                : null);
        save(
                "certificate-verification",
                request.operationNo(),
                new StoredCertificateVerification(
                        PricingSnapshotHash.of(request), query, result, queries));
        return result;
    }

    private CertificateAction certificateAction(
            CertificateBook book, ChannelRedemptionGateway.Action action) {
        var original =
                book.actions().stream()
                        .filter(a -> a.action().operationNo().equals(action.operationNo()))
                        .findFirst()
                        .orElse(null);
        if (original != null)
            require(original.requestHash().equals(PricingSnapshotHash.of(action)));
        return original;
    }

    private Object redeemCertificate(ChannelRedemptionGateway.Action action, boolean reverse) {
        require(action != null && (action.originalRedemptionOperationNo() != null) == reverse);
        certificateScope(action.scope());
        var book = certificateBook(action.scope(), action.certificateIdentityHash());
        var previous = certificateAction(book, action);
        if (previous != null) return previous.result();
        require(
                action.sourceAuthorizationRef() != null
                        && action.sourceHash() != null
                        && action.unit().equals(book.seed().certificate().unit())
                        && book.actions().size() < 5000);
        ChannelRedemptionPolicy.quantity(action.quantity(), false);
        ChannelRedemptionPolicy.hash(action.sourceHash());
        if (!reverse) {
            var original =
                    load(
                            "certificate-verification",
                            action.originalVerificationOperationNo(),
                            StoredCertificateVerification.class);
            require(
                    original != null
                            && original.result().status()
                                    == ChannelRedemptionGateway.Status.CONFIRMED
                            && original.query().request().scope().equals(action.scope())
                            && original.result()
                                    .certificate()
                                    .certificateIdentityHash()
                                    .equals(action.certificateIdentityHash())
                            && original.result()
                                    .certificate()
                                    .originalReceiptRef()
                                    .equals(action.originalVerificationReceiptRef())
                            && original.result().certificate().validUntil().isAfter(Instant.now())
                            && action.quantity()
                                            .compareTo(
                                                    original.result()
                                                            .certificate()
                                                            .remainingQuantity())
                                    <= 0);
            require(
                    book.actions().stream()
                            .noneMatch(
                                    a ->
                                            a.action()
                                                    .sourceAuthorizationRef()
                                                    .equals(action.sourceAuthorizationRef())));
        }
        var status =
                switch (book.seed().scenario()) {
                    case ASYNC, UNKNOWN, PARTIAL -> ChannelRedemptionGateway.Status.UNKNOWN;
                    case REJECT -> ChannelRedemptionGateway.Status.REJECTED;
                    default -> ChannelRedemptionGateway.Status.CONFIRMED;
                };
        var result = persistCertificate(book, action, status, 0, book.seed().scenario());
        afterRecord(action.operationNo());
        return book.seed().scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private ChannelRedemptionGateway.Result queryCertificate(
            ChannelRedemptionGateway.Action action, boolean reverse) {
        require(action != null && (action.originalRedemptionOperationNo() != null) == reverse);
        var book = certificateBook(action.scope(), action.certificateIdentityHash());
        var original = certificateAction(book, action);
        if (original == null)
            return new ChannelRedemptionGateway.Result(
                    action.operationNo(),
                    PricingSnapshotHash.of(action),
                    ChannelRedemptionGateway.Status.UNKNOWN,
                    action.certificateIdentityHash(),
                    null,
                    null,
                    action.unit(),
                    null);
        if (original.result().status() != ChannelRedemptionGateway.Status.UNKNOWN)
            return original.result();
        var status =
                original.scenario() == Scenario.ASYNC && original.queries() >= 1
                        ? ChannelRedemptionGateway.Status.CONFIRMED
                        : ChannelRedemptionGateway.Status.UNKNOWN;
        return persistCertificate(
                book, action, status, original.queries() + 1, original.scenario());
    }

    private ChannelRedemptionGateway.Result resolveCertificate(ResolveCertificate resolution) {
        require(
                resolution != null
                        && resolution.status() != null
                        && resolution.status() != ChannelRedemptionGateway.Status.UNKNOWN);
        var action = resolution.action();
        var book = certificateBook(action.scope(), action.certificateIdentityHash());
        var original = certificateAction(book, action);
        require(original != null);
        if (original.result().status() != ChannelRedemptionGateway.Status.UNKNOWN) {
            require(original.result().status() == resolution.status());
            return original.result();
        }
        return persistCertificate(
                book, action, resolution.status(), original.queries(), original.scenario());
    }

    private ChannelRedemptionGateway.Result persistCertificate(
            CertificateBook book,
            ChannelRedemptionGateway.Action action,
            ChannelRedemptionGateway.Status status,
            int queries,
            Scenario scenario) {
        var consumed = book.consumedQuantity();
        var reversed = book.reversedQuantity();
        boolean reverse = action.originalRedemptionOperationNo() != null;
        if (status == ChannelRedemptionGateway.Status.CONFIRMED) {
            if (!reverse) {
                if (consumed.add(action.quantity())
                                .compareTo(book.seed().certificate().totalQuantity())
                        > 0) status = ChannelRedemptionGateway.Status.REJECTED;
                else consumed = consumed.add(action.quantity());
            } else {
                var originals =
                        book.actions().stream()
                                .filter(
                                        a ->
                                                a.action()
                                                                .operationNo()
                                                                .equals(
                                                                        action
                                                                                .originalRedemptionOperationNo())
                                                        && a.action()
                                                                        .originalRedemptionOperationNo()
                                                                == null
                                                        && a.result().status()
                                                                == ChannelRedemptionGateway.Status
                                                                        .CONFIRMED)
                                .toList();
                boolean already =
                        book.actions().stream()
                                .anyMatch(
                                        a ->
                                                action.originalRedemptionOperationNo()
                                                                .equals(
                                                                        a.action()
                                                                                .originalRedemptionOperationNo())
                                                        && a.result().status()
                                                                == ChannelRedemptionGateway.Status
                                                                        .CONFIRMED);
                if (originals.size() != 1
                        || already
                        || originals.getFirst().action().quantity().compareTo(action.quantity())
                                != 0
                        || consumed.compareTo(action.quantity()) < 0)
                    status = ChannelRedemptionGateway.Status.REJECTED;
                else {
                    consumed = consumed.subtract(action.quantity());
                    reversed = reversed.add(action.quantity());
                }
            }
        }
        var result =
                new ChannelRedemptionGateway.Result(
                        action.operationNo(),
                        PricingSnapshotHash.of(action),
                        status,
                        action.certificateIdentityHash(),
                        status == ChannelRedemptionGateway.Status.UNKNOWN
                                ? null
                                : status == ChannelRedemptionGateway.Status.CONFIRMED
                                        ? action.quantity()
                                        : BigDecimal.ZERO,
                        status == ChannelRedemptionGateway.Status.UNKNOWN
                                ? null
                                : reverse ? reversed : consumed,
                        action.unit(),
                        status == ChannelRedemptionGateway.Status.UNKNOWN
                                ? null
                                : "MOCK:CERTIFICATE_ACTION:" + PricingSnapshotHash.of(action));
        var actions = new ArrayList<>(book.actions());
        actions.removeIf(a -> a.action().operationNo().equals(action.operationNo()));
        actions.add(
                new CertificateAction(
                        PricingSnapshotHash.of(action), action, result, queries, scenario));
        // Original supplier action receipt and exact issuer cumulative quantities commit
        // atomically.
        save(
                "certificate-book",
                certificateKey(
                        action.scope().profileNo(),
                        action.scope().externalAccountRef(),
                        action.certificateIdentityHash()),
                new CertificateBook(book.seed(), consumed, reversed, actions));
        return result;
    }

    private String statementKey(String profile, String account, String reference, String revision) {
        return PricingSnapshotHash.of(List.of(profile, account, reference, revision));
    }

    private StatementSeed seedStatement(StatementSeed seed) {
        require(seed != null && seed.entityNo() != null && seed.scenario() != null);
        ChannelStatementPolicy.document(seed.document(), Clock.systemUTC());
        var d = seed.document();
        require(
                fixtures().stream()
                        .anyMatch(
                                f ->
                                        seed.entityNo().equals(f.entityNo())
                                                && d.profileNo().equals(f.profileNo())
                                                && d.externalAccountRef()
                                                        .equals(f.externalAccountRef())));
        String key =
                statementKey(
                        d.profileNo(),
                        d.externalAccountRef(),
                        d.statementRef(),
                        d.sourceRevision());
        var existing = load("statement", key, StatementSeed.class);
        require(
                existing == null
                        || existing.document().equals(d)
                                && existing.entityNo().equals(seed.entityNo()));
        save("statement", key, seed);
        return seed;
    }

    private ChannelStatementGateway.Receipt statementReceipt(
            ChannelStatementGateway.Lookup lookup,
            ChannelStatementGateway.Document original,
            ChannelStatementGateway.Status status) {
        if (status == ChannelStatementGateway.Status.FOUND) require(original != null);
        String hash = PricingSnapshotHash.of(lookup);
        return new ChannelStatementGateway.Receipt(
                lookup.operationNo(),
                hash,
                status,
                status == ChannelStatementGateway.Status.FOUND ? original : null,
                status == ChannelStatementGateway.Status.FOUND
                        ? PricingSnapshotHash.of(original)
                        : null,
                status == ChannelStatementGateway.Status.UNKNOWN ? null : "MOCK:STATEMENT:" + hash);
    }

    private Object fetchStatement(ChannelStatementGateway.Lookup lookup) {
        ChannelStatementPolicy.lookup(lookup);
        var scope = lookup.scope();
        require(
                fixtures().stream()
                        .anyMatch(
                                f ->
                                        scope.entityNo().equals(f.entityNo())
                                                && scope.profileNo().equals(f.profileNo())
                                                && scope.externalAccountRef()
                                                        .equals(f.externalAccountRef())));
        var grant = load("grant", scope.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && scope.externalAccountRef().equals(grant.externalAccountRef())
                        && grant.expiresAt().isAfter(Instant.now())
                        && grant.grantedScopes().contains("STATEMENT")
                        && !Boolean.TRUE.equals(
                                load("revoked", scope.credentialRef(), Boolean.class)));
        var existing = load("statement-read", lookup.operationNo(), StoredStatementRead.class);
        if (existing != null) {
            require(existing.lookupHash().equals(PricingSnapshotHash.of(lookup)));
            return existing.receipt();
        }
        var seed =
                load(
                        "statement",
                        statementKey(
                                scope.profileNo(),
                                scope.externalAccountRef(),
                                lookup.statementRef(),
                                lookup.sourceRevision()),
                        StatementSeed.class);
        ChannelStatementGateway.Document original = null;
        Scenario scenario = Scenario.UNKNOWN;
        if (seed != null) {
            require(
                    scope.entityNo().equals(seed.entityNo())
                            && lookup.documentHash()
                                    .equals(PricingSnapshotHash.of(seed.document())));
            original = seed.document();
            scenario = seed.scenario();
        }
        var status =
                original == null || scenario == Scenario.UNKNOWN || scenario == Scenario.ASYNC
                        ? ChannelStatementGateway.Status.UNKNOWN
                        : scenario == Scenario.REJECT
                                ? ChannelStatementGateway.Status.REJECTED
                                : ChannelStatementGateway.Status.FOUND;
        var receipt = statementReceipt(lookup, original, status);
        ChannelStatementPolicy.receipt(lookup, receipt, Clock.systemUTC());
        // Freeze the exact authorized original read and its source document before any simulated
        // timeout.
        save(
                "statement-read",
                lookup.operationNo(),
                new StoredStatementRead(
                        PricingSnapshotHash.of(lookup), lookup, receipt, original, 0, scenario));
        afterRecord(lookup.operationNo());
        return scenario == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : receipt;
    }

    private ChannelStatementGateway.Receipt queryStatement(ChannelStatementGateway.Lookup lookup) {
        ChannelStatementPolicy.lookup(lookup);
        var original = load("statement-read", lookup.operationNo(), StoredStatementRead.class);
        if (original == null)
            return statementReceipt(lookup, null, ChannelStatementGateway.Status.UNKNOWN);
        require(original.lookupHash().equals(PricingSnapshotHash.of(lookup)));
        if (original.receipt().status() != ChannelStatementGateway.Status.UNKNOWN)
            return original.receipt();
        var status =
                original.originalDocument() != null
                                && original.scenario() == Scenario.ASYNC
                                && original.queries() >= 1
                        ? ChannelStatementGateway.Status.FOUND
                        : ChannelStatementGateway.Status.UNKNOWN;
        var receipt = statementReceipt(lookup, original.originalDocument(), status);
        // Only the document frozen by the original active grant is readable. Never re-read the
        // statement source here.
        save(
                "statement-read",
                lookup.operationNo(),
                new StoredStatementRead(
                        original.lookupHash(),
                        lookup,
                        receipt,
                        original.originalDocument(),
                        original.queries() + 1,
                        original.scenario()));
        return receipt;
    }

    private ChannelStatementGateway.Receipt resolveStatementRead(ResolveStatementRead resolution) {
        require(
                resolution != null
                        && resolution.status() != null
                        && resolution.status() != ChannelStatementGateway.Status.UNKNOWN);
        var original = load("statement-read", resolution.operationNo(), StoredStatementRead.class);
        require(original != null);
        if (original.receipt().status() != ChannelStatementGateway.Status.UNKNOWN) {
            require(original.receipt().status() == resolution.status());
            return original.receipt();
        }
        var receipt =
                statementReceipt(
                        original.lookup(), original.originalDocument(), resolution.status());
        save(
                "statement-read",
                resolution.operationNo(),
                new StoredStatementRead(
                        original.lookupHash(),
                        original.lookup(),
                        receipt,
                        original.originalDocument(),
                        original.queries(),
                        original.scenario()));
        return receipt;
    }

    private void availabilityEnvelope(ChannelAvailabilityGateway.Publication p) {
        require(
                p != null
                        && p.operationNo() != null
                        && !p.operationNo().isBlank()
                        && p.generation() > 0
                        && p.scope() != null
                        && p.identity() != null
                        && p.identity().externalRef() != null
                        && p.source() != null
                        && p.source().query() != null
                        && p.sourceReceiptRef() != null
                        && p.source().sourceRevision() != null);
        var q = p.source().query();
        var scope = p.scope();
        require(
                scope.entityNo() != null
                        && scope.storeNo() != null
                        && scope.connectionNo() != null
                        && scope.profileNo() != null
                        && scope.externalAccountRef() != null
                        && scope.externalStoreRef() != null
                        && scope.authorizationNo() != null
                        && scope.credentialRef() != null
                        && scope.connectionRevisionNo() != null
                        && scope.entityNo().equals(q.entityNo())
                        && scope.storeNo().equals(q.storeNo())
                        && q.resourceRef() != null);
        if (q.kind() == ChannelAvailabilitySourcePort.Kind.STOCK) {
            require(
                    p.mappingNo() != null
                            && p.mappingRevisionNo() != null
                            && p.source().state() == ChannelAvailabilitySourcePort.State.QUANTITY
                            && p.source().unit() != null
                            && p.source().quantity() != null
                            && p.source()
                                    .quantity()
                                    .matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?")
                            && new BigDecimal(p.source().quantity()).signum() >= 0);
        } else {
            require(
                    q.kind() == ChannelAvailabilitySourcePort.Kind.STORE
                            && q.owner() != null
                            && q.storeMode() != null
                            && q.storeNo().equals(q.resourceRef())
                            && p.mappingNo() == null
                            && p.mappingRevisionNo() == null
                            && p.identity().externalRef().equals(scope.externalStoreRef())
                            && p.identity().parents().isEmpty()
                            && p.source().quantity() == null
                            && p.source().unit() == null
                            && Set.of(
                                            ChannelAvailabilitySourcePort.State.OPEN,
                                            ChannelAvailabilitySourcePort.State.CLOSED,
                                            ChannelAvailabilitySourcePort.State.SUSPENDED)
                                    .contains(p.source().state()));
            var proof = p.storeIdentityProof();
            require(
                    proof != null
                            && proof.status() == ChannelConnectionIdentityPort.Status.VERIFIED
                            && proof.identity() != null
                            && proof.receiptRef() != null
                            && proof.verifiedAt() != null
                            && proof.validUntil() != null
                            && proof.verifiedAt().isBefore(proof.validUntil())
                            && scope.entityNo().equals(proof.identity().entityNo())
                            && scope.storeNo().equals(proof.identity().storeNo())
                            && scope.externalAccountRef()
                                    .equals(proof.identity().externalAccountRef())
                            && scope.externalStoreRef().equals(proof.identity().externalStoreRef())
                            && scope.profileNo().equals(proof.identity().profileNo()));
        }
    }

    private String availabilityKey(ChannelAvailabilityGateway.Publication p) {
        var s = p.scope();
        if (p.source().query().kind() == ChannelAvailabilitySourcePort.Kind.STORE)
            return PricingSnapshotHash.of(
                    List.of(
                            s.entityNo(),
                            s.storeNo(),
                            s.profileNo(),
                            s.externalAccountRef(),
                            s.externalStoreRef(),
                            s.connectionNo(),
                            p.identity(),
                            p.source().query().kind(),
                            p.source().query().storeMode()));
        return PricingSnapshotHash.of(
                List.of(
                        s.entityNo(),
                        s.storeNo(),
                        s.profileNo(),
                        s.externalAccountRef(),
                        s.externalStoreRef(),
                        s.connectionNo(),
                        p.identity(),
                        p.source().query().kind()));
    }

    private AvailabilityBook availabilityBook(ChannelAvailabilityGateway.Publication p) {
        var book = load("availability-book", availabilityKey(p), AvailabilityBook.class);
        return book == null ? new AvailabilityBook(0, List.of(), null) : book;
    }

    private StoredAvailability availabilityAction(
            AvailabilityBook book, ChannelAvailabilityGateway.Publication p) {
        var action =
                book.actions().stream()
                        .filter(a -> a.publication().operationNo().equals(p.operationNo()))
                        .findFirst()
                        .orElse(null);
        if (action != null) require(action.requestHash().equals(PricingSnapshotHash.of(p)));
        return action;
    }

    private ChannelAvailabilityGateway.Result availabilityResult(
            ChannelAvailabilityGateway.Publication p, ChannelAvailabilityGateway.Status status) {
        String hash = PricingSnapshotHash.of(p);
        return new ChannelAvailabilityGateway.Result(
                p.operationNo(),
                hash,
                status,
                status == ChannelAvailabilityGateway.Status.UNKNOWN
                        ? null
                        : "MOCK:AVAILABILITY:" + hash);
    }

    private Object publishAvailability(ChannelAvailabilityGateway.Publication p) {
        availabilityEnvelope(p);
        var scope = p.scope();
        var fixture =
                fixture(
                        scope.profileNo(),
                        scope.externalAccountRef(),
                        scope.externalStoreRef(),
                        scope.entityNo());
        require(fixture.storeNo().equals(scope.storeNo()));
        var grant = load("grant", scope.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(scope.externalAccountRef())
                        && grant.grantedScopes().contains("AVAILABILITY")
                        && grant.expiresAt().isAfter(Instant.now())
                        && !Boolean.TRUE.equals(
                                load("revoked", scope.credentialRef(), Boolean.class)));
        var book = availabilityBook(p);
        var previous = availabilityAction(book, p);
        if (previous != null) return previous.result();
        if (p.source().query().kind() == ChannelAvailabilitySourcePort.Kind.STORE) {
            require(
                    p.storeIdentityProof()
                                    .identity()
                                    .identityNamespace()
                                    .equals(fixture.identityNamespace())
                            && p.storeIdentityProof()
                                    .receiptRef()
                                    .equals(
                                            "MOCK:"
                                                    + PricingSnapshotHash.of(
                                                            p.storeIdentityProof().identity())));
        } else {
            var catalog = load("catalog", scope.connectionNo() + ":STORE", StoredCatalog.class);
            require(
                    catalog != null
                            && catalog.objects().stream()
                                    .anyMatch(
                                            o ->
                                                    o.type()
                                                                    == ChannelResourceIdentityGateway
                                                                            .ObjectType.SKU
                                                            && o.identity().equals(p.identity())
                                                            && o.sourceRef()
                                                                    .equals(
                                                                            p.source()
                                                                                    .query()
                                                                                    .resourceRef())));
        }
        require(book.actions().size() < 5000 && p.generation() > book.generation());
        var status =
                switch (fixture.scenario()) {
                    case ASYNC, UNKNOWN -> ChannelAvailabilityGateway.Status.UNKNOWN;
                    case REJECT -> ChannelAvailabilityGateway.Status.REJECTED;
                    default -> ChannelAvailabilityGateway.Status.CONFIRMED;
                };
        var result = persistAvailability(book, p, status, 0, fixture.scenario());
        afterRecord(p.operationNo());
        return fixture.scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private ChannelAvailabilityGateway.Result queryAvailability(
            ChannelAvailabilityGateway.Publication p) {
        availabilityEnvelope(p);
        var book = availabilityBook(p);
        var previous = availabilityAction(book, p);
        // The exact frozen operation remains readable after expiry/revoke and catalog replacement.
        if (previous == null)
            return availabilityResult(p, ChannelAvailabilityGateway.Status.UNKNOWN);
        if (previous.result().status() != ChannelAvailabilityGateway.Status.UNKNOWN)
            return previous.result();
        var status =
                previous.scenario() == Scenario.ASYNC && previous.queries() >= 1
                        ? ChannelAvailabilityGateway.Status.CONFIRMED
                        : ChannelAvailabilityGateway.Status.UNKNOWN;
        return persistAvailability(book, p, status, previous.queries() + 1, previous.scenario());
    }

    private ChannelAvailabilityGateway.Result resolveAvailability(ResolveAvailability resolution) {
        require(
                resolution != null
                        && resolution.status() != null
                        && resolution.status() != ChannelAvailabilityGateway.Status.UNKNOWN);
        var p = resolution.publication();
        availabilityEnvelope(p);
        var book = availabilityBook(p);
        var previous = availabilityAction(book, p);
        require(previous != null);
        if (previous.result().status() != ChannelAvailabilityGateway.Status.UNKNOWN) {
            require(previous.result().status() == resolution.status());
            return previous.result();
        }
        return persistAvailability(
                book, p, resolution.status(), previous.queries(), previous.scenario());
    }

    private ChannelAvailabilityGateway.Result persistAvailability(
            AvailabilityBook book,
            ChannelAvailabilityGateway.Publication p,
            ChannelAvailabilityGateway.Status status,
            int queries,
            Scenario scenario) {
        long generation = Math.max(book.generation(), p.generation());
        var effect = book.effect();
        if (status == ChannelAvailabilityGateway.Status.CONFIRMED) {
            if (p.generation() < book.generation())
                status = ChannelAvailabilityGateway.Status.REJECTED;
            else effect = p;
        }
        var result = availabilityResult(p, status);
        var actions = new ArrayList<>(book.actions());
        actions.removeIf(a -> a.publication().operationNo().equals(p.operationNo()));
        actions.add(
                new StoredAvailability(PricingSnapshotHash.of(p), p, result, queries, scenario));
        // Original receipt, high-water generation and actual effect commit in a single atomic owner
        // document.
        save(
                "availability-book",
                availabilityKey(p),
                new AvailabilityBook(generation, actions, effect));
        return result;
    }

    private FulfillmentBook fulfillmentBook(FulfillmentPublication p) {
        var book = load("fulfillment-book", orderKey(p.scope()), FulfillmentBook.class);
        return book == null ? new FulfillmentBook(List.of(), List.of()) : book;
    }

    private StoredFulfillment fulfillmentAction(FulfillmentBook book, FulfillmentPublication p) {
        var existing =
                book.actions().stream()
                        .filter(a -> a.publication().operationNo().equals(p.operationNo()))
                        .findFirst()
                        .orElse(null);
        if (existing != null) require(existing.requestHash().equals(PricingSnapshotHash.of(p)));
        return existing;
    }

    private OrderSeed fulfillmentOriginal(FulfillmentPublication p) {
        orderScope(p.scope(), "FULFILLMENT");
        var original =
                load(
                        "order-revision",
                        orderKey(p.scope()) + ":" + p.scope().externalRevision(),
                        OrderSeed.class);
        require(original != null);
        ChannelFulfillmentPolicy.validate(p, original.document(), Clock.systemUTC());
        return original;
    }

    private PublicationResult fulfillmentResult(
            FulfillmentPublication p, PublicationStatus status) {
        return new PublicationResult(
                p.operationNo(),
                p.scope().externalOrderRef(),
                PricingSnapshotHash.of(p),
                status,
                status == PublicationStatus.UNKNOWN
                        ? null
                        : "MOCK:FULFILLMENT:" + PricingSnapshotHash.of(p));
    }

    private Object publishFulfillment(FulfillmentPublication p) {
        var original = fulfillmentOriginal(p);
        var book = fulfillmentBook(p);
        var previous = fulfillmentAction(book, p);
        if (previous != null) return previous.result();
        require(book.actions().size() < 5000);
        var current = load("order", orderKey(p.scope()), OrderSeed.class);
        require(
                current != null
                        && Set.of(
                                        ChannelOrderGateway.State.ACCEPTED,
                                        ChannelOrderGateway.State.COMPLETED)
                                .contains(current.document().state()));
        var status =
                switch (original.scenario()) {
                    case ASYNC, UNKNOWN -> PublicationStatus.UNKNOWN;
                    case REJECT -> PublicationStatus.REJECTED;
                    default -> PublicationStatus.CONFIRMED;
                };
        var result =
                persistFulfillment(book, p, status, 0, original.scenario(), original.document());
        afterRecord(p.operationNo());
        return original.scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private PublicationResult queryFulfillment(FulfillmentPublication p) {
        var original = fulfillmentOriginal(p);
        var book = fulfillmentBook(p);
        var previous = fulfillmentAction(book, p);
        if (previous == null) return fulfillmentResult(p, PublicationStatus.UNKNOWN);
        var status = previous.result().status();
        if (status == PublicationStatus.UNKNOWN
                && previous.scenario() == Scenario.ASYNC
                && previous.queries() >= 1) status = PublicationStatus.CONFIRMED;
        return persistFulfillment(
                book, p, status, previous.queries() + 1, previous.scenario(), original.document());
    }

    private PublicationResult resolveFulfillment(ResolveFulfillment resolution) {
        require(resolution.status() != null && resolution.status() != PublicationStatus.UNKNOWN);
        var p = resolution.publication();
        var original = fulfillmentOriginal(p);
        var book = fulfillmentBook(p);
        var previous = fulfillmentAction(book, p);
        require(
                previous != null
                        && (previous.result().status() == PublicationStatus.UNKNOWN
                                || previous.result().status() == resolution.status()));
        return persistFulfillment(
                book,
                p,
                resolution.status(),
                previous.queries(),
                Scenario.SUCCESS,
                original.document());
    }

    private PublicationResult persistFulfillment(
            FulfillmentBook book,
            FulfillmentPublication p,
            PublicationStatus status,
            int queries,
            Scenario scenario,
            ChannelOrderGateway.Document original) {
        var effects = new ArrayList<>(book.effects());
        var previous = fulfillmentAction(book, p);
        if (status == PublicationStatus.CONFIRMED
                && (previous == null
                        || previous.result().status() != PublicationStatus.CONFIRMED)) {
            var current = load("order", orderKey(p.scope()), OrderSeed.class);
            if (current == null
                    || !Set.of(
                                    ChannelOrderGateway.State.ACCEPTED,
                                    ChannelOrderGateway.State.COMPLETED)
                            .contains(current.document().state())
                    || !advanceFulfillment(effects, p, original)) {
                status = PublicationStatus.REJECTED;
            }
        }
        var result = fulfillmentResult(p, status);
        var actions = new ArrayList<>(book.actions());
        actions.removeIf(action -> action.publication().operationNo().equals(p.operationNo()));
        actions.add(new StoredFulfillment(PricingSnapshotHash.of(p), p, result, queries, scenario));
        // The original action receipt and all package quantities commit in one atomic owner
        // document.
        save("fulfillment-book", orderKey(p.scope()), new FulfillmentBook(actions, effects));
        return result;
    }

    private boolean advanceFulfillment(
            List<FulfillmentPublication> effects,
            FulfillmentPublication p,
            ChannelOrderGateway.Document original) {
        var prior =
                effects.stream()
                        .filter(
                                effect ->
                                        effect.sourceFulfillmentRef()
                                                .equals(p.sourceFulfillmentRef()))
                        .findFirst()
                        .orElse(null);
        if (prior != null
                && (prior.sourceRevision().equals(p.sourceRevision())
                        || stageRank(prior.stage()) >= stageRank(p.stage())
                        || !prior.lines().equals(p.lines())
                        || !Objects.equals(prior.packageRef(), p.packageRef())
                        || !Objects.equals(prior.carrierCode(), p.carrierCode())
                        || !Objects.equals(prior.trackingNo(), p.trackingNo()))) return false;
        if (p.packageRef() != null
                && effects.stream()
                        .anyMatch(
                                effect ->
                                        p.packageRef().equals(effect.packageRef())
                                                && !effect.sourceFulfillmentRef()
                                                        .equals(p.sourceFulfillmentRef())))
            return false;
        var proposed = new ArrayList<>(effects);
        proposed.removeIf(effect -> effect.sourceFulfillmentRef().equals(p.sourceFulfillmentRef()));
        proposed.add(p);
        for (var ordered : original.lines()) {
            var total =
                    proposed.stream()
                            .filter(effect -> stageRank(effect.stage()) >= stageRank(p.stage()))
                            .flatMap(effect -> effect.lines().stream())
                            .filter(line -> line.externalLineRef().equals(ordered.lineRef()))
                            .map(line -> new BigDecimal(line.quantity()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(new BigDecimal(ordered.quantity())) > 0) return false;
        }
        effects.clear();
        effects.addAll(proposed);
        return true;
    }

    private static int stageRank(Stage stage) {
        return switch (stage) {
            case PREPARING -> 0;
            case READY -> 1;
            case HANDED_OVER, SHIPPED -> 2;
            case DELIVERED, PICKED_UP -> 3;
        };
    }

    private List<Integer> emit(Emission emission) {
        var uri = URI.create(emission.callbackUrl());
        require(
                "http".equals(uri.getScheme())
                        && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost())
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null
                        && uri.getPath()
                                .matches(
                                        "/callback/commerce/MOCK[A-Za-z0-9_-]*/[A-Za-z0-9_-]{1,32}")
                        && emission.credentialRef().startsWith("MOCK:")
                        && emission.copies() > 0
                        && emission.copies() <= 10);
        var statuses = new ArrayList<Integer>();
        byte[] body = json.writeValueAsBytes(emission.event());
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature =
                MockCallbackSignatures.sign(
                        new String(token, StandardCharsets.UTF_8),
                        timestamp,
                        emission.credentialRef(),
                        body);
        if (emission.corruptSignature()) signature = "0".repeat(64);
        var client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
        for (int i = 0; i < emission.copies(); i++) {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("X-Mock-Timestamp", timestamp)
                            .header("X-Mock-Credential-Ref", emission.credentialRef())
                            .header("X-Mock-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            try {
                statuses.add(
                        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Mock callback interrupted");
            } catch (IOException unavailable) {
                throw new IllegalStateException("Mock callback unavailable");
            }
        }
        return statuses;
    }

    private Fixture fixture(Fixture f) {
        require(
                f != null
                        && f.entityNo() != null
                        && f.storeNo() != null
                        && f.profileNo() != null
                        && f.profileNo().startsWith("MOCK")
                        && f.identityNamespace() != null
                        && f.identityNamespace().startsWith("MOCK")
                        && f.externalAccountRef() != null
                        && f.externalStoreRef() != null
                        && f.scopes() != null
                        && f.scenario() != null);
        save("fixture", f.profileNo() + ":" + f.externalStoreRef(), f);
        return f;
    }

    private Fixture fixture(String profile, String account, String store, String entity) {
        var f = load("fixture", profile + ":" + store, Fixture.class);
        require(f != null && f.externalAccountRef().equals(account) && f.entityNo().equals(entity));
        return f;
    }

    private ChannelConnectionIdentityPort.Proof verify(
            ChannelConnectionIdentityPort.Identity identity) {
        var f =
                fixture(
                        identity.profileNo(),
                        identity.externalAccountRef(),
                        identity.externalStoreRef(),
                        identity.entityNo());
        require(
                f.storeNo().equals(identity.storeNo())
                        && f.identityNamespace().equals(identity.identityNamespace()));
        var now = Instant.now();
        return new ChannelConnectionIdentityPort.Proof(
                f.scenario() == Scenario.UNKNOWN
                        ? ChannelConnectionIdentityPort.Status.UNKNOWN
                        : ChannelConnectionIdentityPort.Status.VERIFIED,
                identity,
                "MOCK:" + PricingSnapshotHash.of(identity),
                now,
                now.plusSeconds(30));
    }

    private ChannelAuthorizationGateway.Start begin(
            ChannelAuthorizationGateway.AuthorizationContext context) {
        require(context.profileNo().startsWith("MOCK") && context.state() != null);
        var existing = load("authorization", context.operationNo(), StoredAuthorization.class);
        require(existing == null || existing.context().equals(context));
        if (existing == null)
            save(
                    "authorization",
                    context.operationNo(),
                    new StoredAuthorization(context, Instant.now().plusSeconds(900)));
        return new ChannelAuthorizationGateway.Start(
                "https://commerce-mock.invalid/authorize?state="
                        + URLEncoder.encode(context.state(), StandardCharsets.UTF_8)
                        + "&redirect_uri="
                        + URLEncoder.encode(
                                "https://commerce-mock.invalid/callback", StandardCharsets.UTF_8),
                "MOCK:" + PricingSnapshotHash.of(context));
    }

    private ChannelAuthorizationGateway.Grant exchange(Exchange request) {
        var c = request.context();
        var authorization = load("authorization", c.operationNo(), StoredAuthorization.class);
        require(
                "mock-approved".equals(request.code())
                        && authorization != null
                        && c.equals(authorization.context())
                        && authorization.expiresAt().isAfter(Instant.now()));
        var fixtures = fixtures();
        var f =
                fixtures.stream()
                        .filter(
                                x ->
                                        x.profileNo().equals(c.profileNo())
                                                && x.externalAccountRef()
                                                        .equals(c.externalAccountRef())
                                                && x.entityNo().equals(c.entityNo()))
                        .findFirst()
                        .orElseThrow();
        var original =
                load("grant", "MOCK:" + c.operationNo(), ChannelAuthorizationGateway.Grant.class);
        if (original != null) return original;
        var now = Instant.now();
        var grant =
                new ChannelAuthorizationGateway.Grant(
                        c.externalAccountRef(),
                        "MOCK:" + c.operationNo(),
                        f.scopes(),
                        now,
                        now.plusSeconds(86400),
                        "MOCK:" + PricingSnapshotHash.of(c));
        save("grant", grant.credentialRef(), grant);
        return grant;
    }

    private ChannelAuthorizationGateway.Revocation revoke(
            ChannelAuthorizationGateway.RevocationContext context) {
        require(
                context.profileNo().startsWith("MOCK")
                        && context.credentialRef().startsWith("MOCK:"));
        var grant = load("grant", context.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(context.externalAccountRef())
                        && fixtures().stream()
                                .anyMatch(
                                        f ->
                                                f.entityNo().equals(context.entityNo())
                                                        && f.profileNo().equals(context.profileNo())
                                                        && f.externalAccountRef()
                                                                .equals(
                                                                        context
                                                                                .externalAccountRef())));
        var previous = load("revocation", context.operationNo(), StoredRevocation.class);
        if (previous != null) {
            require(previous.context().equals(context));
            save("revoked", context.credentialRef(), Boolean.TRUE);
            return previous.result();
        }
        var result =
                new ChannelAuthorizationGateway.Revocation(
                        true, "MOCK:" + PricingSnapshotHash.of(context));
        save("revocation", context.operationNo(), new StoredRevocation(context, result));
        afterRecord(context.operationNo());
        save("revoked", context.credentialRef(), Boolean.TRUE);
        return result;
    }

    private ChannelAuthorizationGateway.AuthorizationRecovery queryAuthorization(
            ChannelAuthorizationGateway.AuthorizationQuery query) {
        require(query.profileNo().startsWith("MOCK"));
        var stored = load("authorization", query.operationNo(), StoredAuthorization.class);
        if (stored == null)
            return new ChannelAuthorizationGateway.AuthorizationRecovery(
                    query, ChannelAuthorizationGateway.RecoveryStatus.UNKNOWN, null, null, null);
        var c = stored.context();
        require(
                c.entityNo().equals(query.entityNo())
                        && c.accountNo().equals(query.accountNo())
                        && c.profileNo().equals(query.profileNo())
                        && c.externalAccountRef().equals(query.externalAccountRef())
                        && c.actorNo().equals(query.actorNo())
                        && c.returnTargetCode().equals(query.returnTargetCode()));
        String receipt = "MOCK:AUTH_QUERY:" + PricingSnapshotHash.of(query);
        if (query.callbackConsumed()) {
            var grant =
                    load(
                            "grant",
                            "MOCK:" + query.operationNo(),
                            ChannelAuthorizationGateway.Grant.class);
            if (grant != null
                    && grant.expiresAt().isAfter(Instant.now())
                    && !Boolean.TRUE.equals(load("revoked", grant.credentialRef(), Boolean.class)))
                return new ChannelAuthorizationGateway.AuthorizationRecovery(
                        query,
                        ChannelAuthorizationGateway.RecoveryStatus.FOUND,
                        null,
                        grant,
                        receipt);
            // The mock owner guarantees no new exchange after its own immutable expiry, not the
            // caller's local timer.
            if (!stored.expiresAt().isAfter(Instant.now()) || grant != null)
                return new ChannelAuthorizationGateway.AuthorizationRecovery(
                        query,
                        ChannelAuthorizationGateway.RecoveryStatus.TERMINAL_FAILED,
                        null,
                        null,
                        receipt);
            return new ChannelAuthorizationGateway.AuthorizationRecovery(
                    query, ChannelAuthorizationGateway.RecoveryStatus.UNKNOWN, null, null, null);
        }
        if (!stored.expiresAt().isAfter(Instant.now()))
            return new ChannelAuthorizationGateway.AuthorizationRecovery(
                    query,
                    ChannelAuthorizationGateway.RecoveryStatus.TERMINAL_FAILED,
                    null,
                    null,
                    receipt);
        return new ChannelAuthorizationGateway.AuthorizationRecovery(
                query, ChannelAuthorizationGateway.RecoveryStatus.FOUND, begin(c), null, receipt);
    }

    private ChannelAuthorizationGateway.RevocationRecovery queryRevocation(
            ChannelAuthorizationGateway.RevocationContext query) {
        var stored = load("revocation", query.operationNo(), StoredRevocation.class);
        if (stored == null)
            return new ChannelAuthorizationGateway.RevocationRecovery(
                    query, ChannelAuthorizationGateway.RecoveryStatus.UNKNOWN, null, null);
        require(stored.context().equals(query));
        save("revoked", query.credentialRef(), Boolean.TRUE);
        return new ChannelAuthorizationGateway.RevocationRecovery(
                query,
                ChannelAuthorizationGateway.RecoveryStatus.FOUND,
                stored.result(),
                "MOCK:REVOKE_QUERY:" + PricingSnapshotHash.of(query));
    }

    private Object publish(ChannelCatalogGateway.Publication publication) {
        require(
                publication.credentialRef().startsWith("MOCK:")
                        && !Boolean.TRUE.equals(
                                load("revoked", publication.credentialRef(), Boolean.class)));
        var fixture =
                fixtures().stream()
                        .filter(
                                f ->
                                        f.entityNo().equals(publication.entityNo())
                                                && f.storeNo().equals(publication.storeNo())
                                                && f.externalStoreRef()
                                                        .equals(publication.externalStoreRef()))
                        .findFirst()
                        .orElseThrow();
        var grant =
                load("grant", publication.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(fixture.externalAccountRef())
                        && grant.expiresAt().isAfter(Instant.now())
                        && grant.grantedScopes().contains("CATALOG"));
        CatalogDocumentPolicy.freeze(
                publication.document(),
                publication.document().sources(),
                json,
                publication.entityNo(),
                publication.storeNo());
        require(
                CatalogDocumentPolicy.hash(publication.document(), json)
                        .equals(publication.contentHash()));
        String hash = PricingSnapshotHash.of(publication);
        var previous = load("operation", publication.operationNo(), StoredOperation.class);
        if (previous != null) {
            require(previous.requestHash().equals(hash));
            if (applied(previous.result())) apply(publication, previous.result());
            return previous.result();
        }
        var current = load("catalog", scope(publication), StoredCatalog.class);
        if (current != null) require(publication.generation() > current.generation());
        var outcome =
                switch (fixture.scenario()) {
                    case ASYNC -> ChannelCatalogGateway.Outcome.SUBMITTED;
                    case UNKNOWN -> ChannelCatalogGateway.Outcome.UNKNOWN;
                    case PARTIAL -> ChannelCatalogGateway.Outcome.PARTIAL;
                    case REJECT -> ChannelCatalogGateway.Outcome.FAILED;
                    default -> ChannelCatalogGateway.Outcome.SUCCEEDED;
                };
        var result = result(publication, outcome);
        save(
                "operation",
                publication.operationNo(),
                new StoredOperation(hash, publication, result, 0, fixture.scenario()));
        afterRecord(publication.operationNo());
        if (outcome == ChannelCatalogGateway.Outcome.SUCCEEDED
                || outcome == ChannelCatalogGateway.Outcome.PARTIAL) apply(publication, result);
        return fixture.scenario() == Scenario.PERSIST_THEN_TIMEOUT ? Timeout.INSTANCE : result;
    }

    private ChannelCatalogGateway.Result resolve(Resolve request) {
        require(
                request != null
                        && Set.of(
                                        ChannelCatalogGateway.Outcome.SUCCEEDED,
                                        ChannelCatalogGateway.Outcome.FAILED,
                                        ChannelCatalogGateway.Outcome.PARTIAL,
                                        ChannelCatalogGateway.Outcome.CANCELLED)
                                .contains(request.outcome()));
        var previous = load("operation", request.operationNo(), StoredOperation.class);
        require(previous != null);
        require(
                !previous.result().allWritesTerminal()
                        || previous.result().outcome() == request.outcome());
        var current = load("catalog", scope(previous.publication()), StoredCatalog.class);
        require(
                previous.result().allWritesTerminal()
                        || current == null
                        || current.generation() <= previous.publication().generation()
                        || request.outcome() == ChannelCatalogGateway.Outcome.CANCELLED);
        var result = result(previous.publication(), request.outcome());
        save(
                "operation",
                request.operationNo(),
                new StoredOperation(
                        previous.requestHash(),
                        previous.publication(),
                        result,
                        previous.queries(),
                        Scenario.SUCCESS));
        if (request.outcome() == ChannelCatalogGateway.Outcome.SUCCEEDED
                || request.outcome() == ChannelCatalogGateway.Outcome.PARTIAL)
            apply(previous.publication(), result);
        return result;
    }

    private ChannelCatalogGateway.Result query(CatalogQuery query) {
        var p = query.publication();
        var op = load("operation", p.operationNo(), StoredOperation.class);
        if (op == null) return result(p, ChannelCatalogGateway.Outcome.UNKNOWN);
        require(
                op.requestHash().equals(PricingSnapshotHash.of(p))
                        && p.operationNo().equals(query.taskRef()));
        var result = op.result();
        var current = load("catalog", scope(p), StoredCatalog.class);
        if (!result.allWritesTerminal()
                && current != null
                && current.generation() > p.generation()) {
            result = result(p, ChannelCatalogGateway.Outcome.CANCELLED);
        } else if (!result.allWritesTerminal()
                && op.scenario() == Scenario.ASYNC
                && op.queries() >= 1) {
            result = result(p, ChannelCatalogGateway.Outcome.SUCCEEDED);
        }
        save(
                "operation",
                p.operationNo(),
                new StoredOperation(op.requestHash(), p, result, op.queries() + 1, op.scenario()));
        if (applied(result)) apply(p, result);
        return result;
    }

    private ChannelCatalogGateway.Result result(
            ChannelCatalogGateway.Publication p, ChannelCatalogGateway.Outcome outcome) {
        var objects = objects(p.document());
        var items = new ArrayList<ChannelCatalogGateway.ItemResult>();
        if (outcome == ChannelCatalogGateway.Outcome.SUCCEEDED
                || outcome == ChannelCatalogGateway.Outcome.PARTIAL) {
            for (int i = 0; i < objects.size(); i++) {
                var o = objects.get(i);
                boolean ok = outcome == ChannelCatalogGateway.Outcome.SUCCEEDED || i == 0;
                items.add(
                        new ChannelCatalogGateway.ItemResult(
                                o.type().name(),
                                o.key(),
                                o.identity().externalRef(),
                                o.sourceRef(),
                                ok ? "SUCCEEDED" : "FAILED",
                                ok ? null : "MOCK_PARTIAL",
                                "MOCK:" + p.operationNo()));
            }
        }
        boolean terminal =
                outcome == ChannelCatalogGateway.Outcome.SUCCEEDED
                        || outcome == ChannelCatalogGateway.Outcome.PARTIAL
                        || outcome == ChannelCatalogGateway.Outcome.FAILED
                        || outcome == ChannelCatalogGateway.Outcome.CANCELLED;
        return new ChannelCatalogGateway.Result(
                p.operationNo(),
                p.generation(),
                p.contentHash(),
                outcome,
                terminal,
                p.operationNo(),
                "MOCK:" + p.operationNo(),
                items);
    }

    private static boolean applied(ChannelCatalogGateway.Result result) {
        return result.outcome() == ChannelCatalogGateway.Outcome.SUCCEEDED
                || result.outcome() == ChannelCatalogGateway.Outcome.PARTIAL;
    }

    private void apply(ChannelCatalogGateway.Publication p, ChannelCatalogGateway.Result result) {
        var existing = load("catalog", scope(p), StoredCatalog.class);
        if (existing != null && existing.generation() > p.generation()) return;
        if (existing != null && existing.generation() == p.generation()) {
            require(
                    existing.operationNo().equals(p.operationNo())
                            && existing.contentHash().equals(p.contentHash()));
            return;
        }
        var objects = objects(p.document());
        if (result.outcome() == ChannelCatalogGateway.Outcome.PARTIAL) {
            var previous = load("catalog", scope(p), StoredCatalog.class);
            var partial =
                    new ArrayList<RemoteObject>(previous == null ? List.of() : previous.objects());
            if (!objects.isEmpty()) {
                var first = objects.getFirst();
                partial.removeIf(o -> o.identity().equals(first.identity()));
                partial.add(first);
            }
            objects = partial;
        }
        save(
                "catalog",
                scope(p),
                new StoredCatalog(p.operationNo(), p.generation(), p.contentHash(), objects));
    }

    private ChannelResourceIdentityGateway.Proof resource(ChannelResourceIdentityGateway.Query q) {
        fixture(q.profileNo(), q.externalAccountRef(), q.externalStoreRef(), q.entityNo());
        var grant = load("grant", q.credentialRef(), ChannelAuthorizationGateway.Grant.class);
        require(
                grant != null
                        && grant.externalAccountRef().equals(q.externalAccountRef())
                        && grant.expiresAt().isAfter(Instant.now())
                        && grant.grantedScopes().contains("CATALOG")
                        && !Boolean.TRUE.equals(load("revoked", q.credentialRef(), Boolean.class)));
        var catalog = load("catalog", q.connectionNo() + ":STORE", StoredCatalog.class);
        boolean found =
                catalog != null
                        && catalog.objects().stream()
                                .anyMatch(
                                        o ->
                                                o.type() == q.objectType()
                                                        && o.identity().equals(q.identity()));
        return new ChannelResourceIdentityGateway.Proof(
                found
                        ? ChannelResourceIdentityGateway.Status.VERIFIED
                        : ChannelResourceIdentityGateway.Status.NOT_FOUND,
                q.entityNo(),
                q.storeNo(),
                q.connectionNo(),
                q.objectType(),
                q.identity(),
                "MOCK:" + PricingSnapshotHash.of(q),
                Instant.now().plusSeconds(30));
    }

    private static String scope(ChannelCatalogGateway.Publication p) {
        return p.connectionNo() + ":" + p.remoteScopeKey();
    }

    private static List<RemoteObject> objects(Document d) {
        var list = new ArrayList<RemoteObject>();
        var identities =
                new HashMap<CatalogResourceIndex.Key, ChannelResourceIdentityGateway.Identity>();
        for (var resource : CatalogResourceIndex.resources(d)) {
            var parents =
                    resource.parents().stream()
                            .map(
                                    parent ->
                                            new ChannelResourceIdentityGateway.Parent(
                                                    parent.type(),
                                                    identities.get(parent).externalRef()))
                            .toList();
            var object =
                    object(
                            resource.key().type(),
                            resource.key().value(),
                            resource.sourceRef(),
                            parents);
            list.add(object);
            identities.put(resource.key(), object.identity());
        }
        return List.copyOf(list);
    }

    private static RemoteObject object(
            ChannelResourceIdentityGateway.ObjectType type,
            String key,
            String source,
            List<ChannelResourceIdentityGateway.Parent> parents) {
        return new RemoteObject(
                type,
                key,
                source,
                new ChannelResourceIdentityGateway.Identity(
                        "MOCK:" + PricingSnapshotHash.of(List.of(type, key)), parents));
    }

    private List<Fixture> fixtures() {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("fixture-"))
                    .map(p -> readFile(p, Fixture.class))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Mock storage unavailable", e);
        }
    }

    private Path file(String kind, String id) {
        return directory.resolve(kind + "-" + PricingSnapshotHash.of(id) + ".json");
    }

    private <T> T load(String kind, String id, Class<T> type) {
        Path path = file(kind, id);
        return Files.exists(path) ? readFile(path, type) : null;
    }

    private <T> T readFile(Path path, Class<T> type) {
        try {
            return json.readValue(Files.readAllBytes(path), type);
        } catch (IOException e) {
            throw new IllegalStateException("Mock evidence read failed", e);
        }
    }

    private void save(String kind, String id, Object value) {
        try {
            Path pending = Files.createTempFile(directory, "pending-", ".json");
            try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(json.writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(
                    pending,
                    file(kind, id),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Mock evidence commit failed", e);
        }
    }

    private void respond(HttpExchange e, int status, Object value) throws IOException {
        byte[] body = value == null ? new byte[0] : json.writeValueAsBytes(value);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, body.length);
        e.getResponseBody().write(body);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid mock request");
    }
}
