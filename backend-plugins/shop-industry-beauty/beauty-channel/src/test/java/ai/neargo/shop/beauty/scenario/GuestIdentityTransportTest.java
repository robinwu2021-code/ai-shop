package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.beauty.port.RemoteCustomerIdentityPort;
import ai.neargo.shop.beauty.port.RemoteGuestRegistrationPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real TCP tests treat malformed or mismatched owner echoes, including HTTP200 error bodies, as unavailable. */
class GuestIdentityTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DelegatedIdentity ID = new DelegatedIdentity("MAIN", "MERCHANT", "USR", "U");
    private static final Scope SCOPE = new Scope("E", "S", ID);
    @Test void guestOperationsRequireFullScopeCommandAndResultEcho() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteGuestRegistrationPort(tcp.http().client("PLATFORM", GuestRegistrationInternalApi.class,
                    Duration.ofSeconds(2)));
            var command = new GuestRegistrationPort.RegisterCommand("contact", "Guest", true, "original-key");
            DelegatedIdentityContext.call(ID, () -> {
                tcp.reply = body -> {
                    var request = JSON.readValue(body, GuestRegistrationInternalApi.Register.class);
                    assertThat(request.scope()).isEqualTo(SCOPE); assertThat(request.command()).isEqualTo(command);
                    return json(new GuestRegistrationInternalApi.Registered(request,
                            new GuestRegistrationPort.Registration("G", "a".repeat(43), "REGISTERED", 1)));
                };
                assertThat(port.register(SCOPE, command).guestNo()).isEqualTo("G");
                tcp.reply = body -> json(new GuestRegistrationInternalApi.Registered(
                        new GuestRegistrationInternalApi.Register(new Scope("E", "OTHER", ID), command),
                        new GuestRegistrationPort.Registration("G", "a".repeat(43), "REGISTERED", 1)));
                assertThatThrownBy(() -> port.register(SCOPE, command)).isInstanceOf(IllegalStateException.class);
                tcp.reply = body -> json(new GuestRegistrationInternalApi.Registered(
                        new GuestRegistrationInternalApi.Register(new Scope("E", "S",
                                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "U")), command),
                        new GuestRegistrationPort.Registration("G", "a".repeat(43), "REGISTERED", 1)));
                assertThatThrownBy(() -> port.register(SCOPE, command)).isInstanceOf(IllegalStateException.class);
                tcp.reply = body -> {
                    var request = JSON.readValue(body, GuestRegistrationInternalApi.Get.class);
                    return json(new GuestRegistrationInternalApi.Found(request,
                            new GuestRegistrationPort.GuestFact("G", "REGISTERED", null, 1)));
                };
                assertThat(port.get(SCOPE, "G").state()).isEqualTo("REGISTERED");
                tcp.reply = body -> {
                    var request = JSON.readValue(body, GuestRegistrationInternalApi.Verify.class);
                    assertThat(request.claimToken()).isEqualTo("secret");
                    return json(new GuestRegistrationInternalApi.Verified(request,
                            new GuestRegistrationPort.GuestFact("G", "REGISTERED", null, 1)));
                };
                assertThat(port.verify(SCOPE, "G", "secret").guestNo()).isEqualTo("G");
                tcp.reply = body -> json(new GuestRegistrationInternalApi.Verified(
                        new GuestRegistrationInternalApi.Verify(SCOPE, "G", "different-secret"),
                        new GuestRegistrationPort.GuestFact("G", "REGISTERED", null, 1)));
                assertThatThrownBy(() -> port.verify(SCOPE, "G", "secret")).isInstanceOf(IllegalStateException.class);
                var claim = new GuestRegistrationPort.ClaimCommand("cr1", "secret", "claim-key");
                tcp.reply = body -> {
                    var request = JSON.readValue(body, GuestRegistrationInternalApi.ClaimRequest.class);
                    assertThat(request.command()).isEqualTo(claim);
                    return json(new GuestRegistrationInternalApi.Claimed(request,
                            new GuestRegistrationPort.Claim("G", "CUSTOMER", 2)));
                };
                assertThat(port.claim(SCOPE, "G", claim).userNo()).isEqualTo("CUSTOMER");
                tcp.reply = body -> json(new GuestRegistrationInternalApi.Claimed(
                        new GuestRegistrationInternalApi.ClaimRequest(SCOPE, "G",
                                new GuestRegistrationPort.ClaimCommand("cr1", "secret", "different-key")),
                        new GuestRegistrationPort.Claim("G", "CUSTOMER", 2)));
                assertThatThrownBy(() -> port.claim(SCOPE, "G", claim)).isInstanceOf(IllegalStateException.class);
                tcp.reply = body -> "{\"code\":40042,\"message\":\"unavailable\"}";
                assertThatThrownBy(() -> port.get(SCOPE, "G")).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> port.verify(SCOPE, "G", "secret")).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> port.claim(SCOPE, "G", claim)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> port.register(SCOPE, command)).isInstanceOf(RuntimeException.class);
                int before = tcp.calls.get();
                assertThatThrownBy(() -> port.get(new Scope("E", "S",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "U")), "G"))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(tcp.calls.get()).isEqualTo(before); return null;
            });
        }
    }
    @Test void consumerProofMustEchoCurrentConsumerContextOriginalCodeAndActualSameUser() throws Exception {
        try (var tcp = new Tcp()) {
            var port = new RemoteCustomerIdentityPort(tcp.http().client("PLATFORM", CustomerIdentityInternalApi.class,
                    Duration.ofSeconds(2)));
            var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "C");
            var c = new EntitlementContext("MAIN", "C", "request", "b".repeat(64));
            DelegatedIdentityContext.call(consumer, () -> {
                tcp.reply = body -> {
                    var req = JSON.readValue(body, CustomerIdentityInternalApi.ProofRequest.class);
                    assertThat(req.identity()).isEqualTo(consumer); assertThat(req.memberCode()).isEqualTo("mc1");
                    return json(new CustomerIdentityInternalApi.ProofResolution(req,
                            new CustomerIdentityPort.ResolvedCustomer("C")));
                };
                assertThat(port.verifyConsumerProof(c, "mc1").userNo()).isEqualTo("C");
                tcp.reply = body -> json(new CustomerIdentityInternalApi.ProofResolution(
                        new CustomerIdentityInternalApi.ProofRequest(consumer, c, "different"),
                        new CustomerIdentityPort.ResolvedCustomer("C")));
                assertThatThrownBy(() -> port.verifyConsumerProof(c, "mc1")).isInstanceOf(IllegalStateException.class);
                tcp.reply = body -> json(new CustomerIdentityInternalApi.ProofResolution(
                        new CustomerIdentityInternalApi.ProofRequest(consumer, c, "mc1"),
                        new CustomerIdentityPort.ResolvedCustomer("OTHER")));
                assertThatThrownBy(() -> port.verifyConsumerProof(c, "mc1")).isInstanceOf(IllegalStateException.class);
                return null;
            });
        }
    }
    @Test void remoteOwnerConfigurationHasExactlyOneGuestProviderAndPreservesExistingLocalProvider() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BeautyMerchantClientConfiguration.class)
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> Optional.empty(), "key"));
        runner.run(c -> assertThat(c.getBeansOfType(GuestRegistrationPort.class)).hasSize(1));
        var local = new RemoteGuestRegistrationPort(null);
        runner.withBean("existingGuest", GuestRegistrationPort.class, () -> local).run(c -> {
            assertThat(c.getBeansOfType(GuestRegistrationPort.class)).hasSize(1);
            assertThat(c.getBean(GuestRegistrationPort.class)).isSameAs(local);
        });
    }
    @Test void writeTransportClassificationDoesNotTurnOwnerRejectionIntoPending() {
        var api = org.mockito.Mockito.mock(GuestRegistrationInternalApi.class);
        var port = new RemoteGuestRegistrationPort(api);
        var register = new GuestRegistrationPort.RegisterCommand("contact", "Guest", true, "key");
        var claim = new GuestRegistrationPort.ClaimCommand("cr1", "token", "key");
        DelegatedIdentityContext.call(ID, () -> {
            for (var outcome : new CallOutcome[]{CallOutcome.TIMEOUT, CallOutcome.UNREACHABLE,
                    CallOutcome.REMOTE_ERROR}) {
                var failure = new ServiceCallException(outcome, "PLATFORM", 0, "unknown", null);
                org.mockito.Mockito.when(api.register(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
                org.mockito.Mockito.when(api.claim(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
                assertThatThrownBy(() -> port.register(SCOPE, register))
                        .isInstanceOf(GuestRegistrationPort.ResultUnknown.class).hasNoCause();
                assertThatThrownBy(() -> port.claim(SCOPE, "G", claim))
                        .isInstanceOf(GuestRegistrationPort.ResultUnknown.class).hasNoCause();
                org.mockito.Mockito.reset(api);
            }
            for (int status : new int[]{0, 403, 503}) {
                var outcome = status == 0 ? CallOutcome.NOT_CONFIGURED : CallOutcome.REMOTE_ERROR;
                var failure = new ServiceCallException(outcome, "PLATFORM", status, "rejected", null);
                org.mockito.Mockito.when(api.register(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
                org.mockito.Mockito.when(api.claim(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
                assertThatThrownBy(() -> port.register(SCOPE, register)).isSameAs(failure);
                assertThatThrownBy(() -> port.claim(SCOPE, "G", claim)).isSameAs(failure);
                org.mockito.Mockito.reset(api);
            }
            return null;
        });
    }
    @Test void actualHttpDenialsMissingAddressAndUnreadableResponseAreClassified() throws Exception {
        var command = new GuestRegistrationPort.RegisterCommand("private-contact", "Guest", true, "key");
        try (var tcp = new Tcp()) {
            var port = new RemoteGuestRegistrationPort(tcp.http().client("PLATFORM", GuestRegistrationInternalApi.class,
                    Duration.ofSeconds(2)));
            DelegatedIdentityContext.call(ID, () -> {
                for (int status : new int[]{403, 503}) {
                    tcp.status = status;
                    assertThatThrownBy(() -> port.register(SCOPE, command))
                            .isInstanceOfSatisfying(ServiceCallException.class,
                                    failure -> assertThat(failure.statusCode()).isEqualTo(status));
                }
                tcp.status = 200; tcp.reply = body -> "not-json";
                assertThatThrownBy(() -> port.register(SCOPE, command))
                        .isInstanceOf(GuestRegistrationPort.ResultUnknown.class)
                        .hasMessage("guest owner result unknown").hasNoCause();
                var absent = new InternalHttp(service -> Optional.empty(), "key");
                var unavailable = new RemoteGuestRegistrationPort(absent.client("PLATFORM",
                        GuestRegistrationInternalApi.class, Duration.ofSeconds(2)));
                assertThatThrownBy(() -> unavailable.register(SCOPE, command))
                        .isInstanceOfSatisfying(ServiceCallException.class,
                                failure -> assertThat(failure.outcome()).isEqualTo(CallOutcome.NOT_CONFIGURED));
                return null;
            });
        }
    }
    private static String json(Object value) { return JSON.writeValueAsString(value); }
    private static final class Tcp implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        Function<String, String> reply = body -> "{}";
        int status = 200;
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
                    exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            server.start();
        }
        InternalHttp http() {
            return new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "key");
        }
        @Override public void close() { server.stop(0); }
    }
}
