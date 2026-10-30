package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.port.RemoteAppointmentSourceAuthorityPort;
import ai.neargo.shop.beauty.port.RemoteCustomerIdentityPort;
import ai.neargo.shop.beauty.port.RemoteResourceBookingPort;
import ai.neargo.shop.beauty.port.RemoteResourceCandidatesPort;
import ai.neargo.shop.beauty.port.RemoteServiceCatalogFactsPort;
import ai.neargo.shop.beauty.spi.BeautySourceInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real TCP malformed/identity/outage tests; HTTP fixtures delimit transport evidence, not owner acceptance. */
class BookingFactsTransportTest {
    private static final String PLAN = ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand(
            "R", "LINE", 1, List.of(new ResourcePlanContract.Interval(Instant.parse("2026-10-22T00:00:00Z"),
                    Instant.parse("2026-10-22T01:00:00Z"))))), List.of());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DelegatedIdentity ID = new DelegatedIdentity("MAIN", "MERCHANT", "USR", "U");
    private static ResourceSourceAuthorityPort.Binding binding(String number) {
        return new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", number, "BEAUTY_APPOINTMENT", "A", 1,
                "RESERVE", "a".repeat(64), ResourcePlanContract.hash(PLAN));
    }
    @Test void explicitLookupAndCompleteBindingRejectMalformedSuccessAndDisconnection() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteResourceBookingPort(tcp.http().client("PLATFORM", ResourceBookingInternalApi.class,
                    Duration.ofSeconds(1)));
            var b = binding("CMD");
            for (String malformed : new String[] {"{}", "null", "{\"code\":10500,\"data\":null}",
                    "{\"found\":true,\"receipt\":null}"}) {
                tcp.reply = request -> malformed;
                assertThatThrownBy(() -> port.reconcile(b)).isInstanceOf(RuntimeException.class);
            }
            tcp.reply = request -> JSON.writeValueAsString(new ResourceBookingInternalApi.Lookup(false, null));
            assertThat(port.reconcile(b)).isEmpty();
            var r = new ResourceBookingPort.Receipt(b, "OWNER", "ACCEPTED", 0, 0, null, PLAN, null, null);
            tcp.reply = request -> JSON.writeValueAsString(new ResourceBookingInternalApi.Lookup(true, r));
            assertThat(port.reconcile(b)).contains(r);
            assertThatThrownBy(() -> port.reconcile(binding("OTHER"))).isInstanceOf(IllegalStateException.class);
            tcp.close();
            assertThatThrownBy(() -> port.reconcile(b)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void capacityCarriesIndependentFactTimeAndCompleteReplacementAndForbidsRpcUnderTransaction() throws
            Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteResourceCandidatesPort(tcp.http().client("PLATFORM", ResourceBookingInternalApi.class,
                    Duration.ofSeconds(1)));
            Instant from = Instant.parse("2026-10-22T00:00:00Z"), at = from.plusSeconds(7200);
            var query = new ResourceCandidatesPort.CapacityQuery(new ResourceBookingPort.Scope("MAIN", "E", "S"),
                    Set.of("R"), from, from.plusSeconds(86400), at,
                    new ResourceCandidatesPort.Replacement(binding("CMD"), new ResourceBookingPort.Target("H", 4, 7)));
            tcp.reply = body -> {
                assertThat(JSON.readValue(body, ResourceCandidatesPort.CapacityQuery.class)).isEqualTo(query);
                return "[]";
            };
            assertThat(port.capacity(query)).isEmpty();
            int before = tcp.calls.get();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> port.capacity(query)).isInstanceOf(IllegalStateException.class); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(tcp.calls.get()).isEqualTo(before);
        }
    }
    @Test void capacityRejectsWrongWindowFactsAndPreservesHalfOpenOverlapSemantics() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteResourceCandidatesPort(tcp.http().client("PLATFORM", ResourceBookingInternalApi.class,
                    Duration.ofSeconds(1)));
            Instant from = Instant.parse("2026-10-22T00:00:00Z"), to = from.plusSeconds(300);
            var query = new ResourceCandidatesPort.CapacityQuery(new ResourceBookingPort.Scope("MAIN", "E", "S"),
                    Set.of("R"), from, to);
            for (var fact : List.of(
                    new ResourceCandidatesPort.Capacity("R", from.plusSeconds(2678400),
                            to.plusSeconds(2678400), 1, 1),
                    new ResourceCandidatesPort.Capacity("R", from.minusSeconds(600), from.minusSeconds(300), 1, 1),
                    new ResourceCandidatesPort.Capacity("R", from.minusSeconds(300), from, 1, 1),
                    new ResourceCandidatesPort.Capacity("R", to, to.plusSeconds(300), 1, 1))) {
                tcp.reply = body -> JSON.writeValueAsString(List.of(fact));
                assertThatThrownBy(() -> port.capacity(query)).isInstanceOf(IllegalStateException.class);
            }
            for (var fact : List.of(
                    new ResourceCandidatesPort.Capacity("R", from.minusSeconds(300), from.plusSeconds(1), 1, 1),
                    new ResourceCandidatesPort.Capacity("R", to.minusSeconds(1), to.plusSeconds(300), 1, 1),
                    new ResourceCandidatesPort.Capacity("R", from, to, 1, 1))) {
                tcp.reply = body -> JSON.writeValueAsString(List.of(fact));
                assertThat(port.capacity(query)).containsExactly(fact);
            }
        }
    }
    @Test void customerReferenceRetainsContextAndUsrNamespaceAndRejectsWrongReturnedIdentity() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteCustomerIdentityPort(tcp.http().client("PLATFORM", CustomerIdentityInternalApi.class,
                    Duration.ofSeconds(1)));
            var c = new EntitlementContext("MAIN", "U", "REQ", "b".repeat(64));
            var q = new CustomerIdentityPort.ReferenceQuery("E", "S", "TICKET");
            tcp.reply = body -> {
                var request = JSON.readValue(body, CustomerIdentityInternalApi.Request.class);
                assertThat(request.identity()).isEqualTo(ID); assertThat(request.context()).isEqualTo(c);
                return JSON.writeValueAsString(new CustomerIdentityInternalApi.Resolution(ID, c, q,
                        new CustomerIdentityPort.ResolvedCustomer("CUSTOMER")));
            };
            DelegatedIdentityContext.call(ID, () -> {
                assertThat(port.resolveReference(c, q).userNo()).isEqualTo("CUSTOMER"); return null;
            });
            tcp.reply = body -> JSON.writeValueAsString(new CustomerIdentityInternalApi.Resolution(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "U"), c, q,
                    new CustomerIdentityPort.ResolvedCustomer("CUSTOMER")));
            DelegatedIdentityContext.call(ID, () -> {
                assertThatThrownBy(() -> port.resolveReference(c,
                        q)).isInstanceOf(IllegalStateException.class); return null;
            });
        }
    }
    @Test void reverseUnknownDoesNotBecomePermissionAndDifferentFullBindingFailsClosed() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteAppointmentSourceAuthorityPort(tcp.http().client("BEAUTY",
                    BeautySourceInternalApi.class,
                    Duration.ofSeconds(1)));
            var b = binding("CMD");
            var request = new ResourceSourceAuthorityPort.Request(b, "U", "MERCHANT", "USR");
            tcp.reply = body -> JSON.writeValueAsString(new ResourceSourceAuthorityPort.Decision(b,
                    ResourceSourceAuthorityPort.State.UNKNOWN, null, null, null, null, null, null));
            DelegatedIdentityContext.call(ID, () -> {
                assertThat(port.query(request).state()).isEqualTo(ResourceSourceAuthorityPort.State.UNKNOWN);
                        return null;
            });
            tcp.reply = body -> JSON.writeValueAsString(new ResourceSourceAuthorityPort.Decision(binding("OTHER"),
                    ResourceSourceAuthorityPort.State.UNKNOWN, null, null, null, null, null, null));
            DelegatedIdentityContext.call(ID, () -> {
                assertThatThrownBy(() -> port.freeze(request)).isInstanceOf(IllegalStateException.class); return null;
            });
        }
    }
    @Test void productMasterRevisionsSurviveTcpAndWrongOrMissingEvidenceIsRejected() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteServiceCatalogFactsPort(tcp.http().client("PLATFORM",
                    ServiceCatalogFactsInternalApi.class,
                    Duration.ofSeconds(1)));
            var store = new ServiceCatalogFactsPort.Store("E", "S", "store", null, "ACTIVE", "", "", null);
            tcp.reply = body -> JSON.writeValueAsString(new ServiceCatalogFactsPort.Service(store, "SKU", "G",
                    "service",
                    null, 1000, 30, 43L, 71L));
            assertThat(port.service("S", "SKU").goodsRevision()).isEqualTo(43);
            assertThat(port.service("S", "SKU").skuRevision()).isEqualTo(71);
            tcp.reply = body -> JSON.writeValueAsString(new ServiceCatalogFactsPort.Service(store, "OTHER", "G",
                    "service",
                    null, 1000, 30, 43L, 71L));
            assertThatThrownBy(() -> port.service("S", "SKU")).isInstanceOf(IllegalStateException.class);
            tcp.reply = body -> JSON.writeValueAsString(new ServiceCatalogFactsPort.Service(store, "SKU", "G",
                    "service",
                    null, 1000, 30, null, null));
            assertThatThrownBy(() -> port.service("S", "SKU")).isInstanceOf(IllegalStateException.class);
        }
    }
    private static class Tcp implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        ThrowingFunction reply = body -> "{}";
        Tcp() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("key");
                    calls.incrementAndGet();
                    byte[] body = reply.apply(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { exchange.close(); }
            });
            server.start();
        }
        InternalHttp http() {
            return new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "key");
        }
        @Override public void close() { server.stop(0); }
    }
    private interface ThrowingFunction { String apply(String body) throws Exception; }
}
