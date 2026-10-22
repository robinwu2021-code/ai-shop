package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual owner HTTP ingress and global advice; guest double delimits this evidence from native owner facts. */
class GuestOwnerMachineTransportTest {
    @Test void tokenAndCurrentIdentityAndFailuresSurviveActualTcpAndGlobalAdvice() throws Exception {
        try (var host = new SpringApplicationBuilder(Host.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--server.address=127.0.0.1", "--shop.services.internal-token=owner-key")) {
            String url = "http://127.0.0.1:" + ((WebServerApplicationContext) host).getWebServer().getPort();
            var http = new InternalHttp(service -> Optional.of(url), "owner-key");
            var api = http.client("PLATFORM", GuestRegistrationInternalApi.class, Duration.ofSeconds(2));
            var proof = http.client("PLATFORM", CustomerIdentityInternalApi.class, Duration.ofSeconds(2));
            var i = new DelegatedIdentity("MAIN", "MERCHANT", "USR", "U");
            var scope = new Scope("E", "S", i);
            var request = new GuestRegistrationInternalApi.Register(scope,
                    new GuestRegistrationPort.RegisterCommand("secret contact", "Guest", true, "key"));
            var result = api.register(request);
            assertThat(result.request()).isEqualTo(request);
            assertThat(result.registration().guestNo()).isEqualTo("GUEST");
            assertThat(api.get(new GuestRegistrationInternalApi.Get(scope, "GUEST")).guest().state())
                    .isEqualTo("REGISTERED");
            assertThat(api.verify(new GuestRegistrationInternalApi.Verify(scope, "GUEST", "secret")).guest().guestNo())
                    .isEqualTo("GUEST");
            assertThat(api.claim(new GuestRegistrationInternalApi.ClaimRequest(scope, "GUEST",
                    new GuestRegistrationPort.ClaimCommand("cr1", "secret", "key"))).claim().userNo())
                    .isEqualTo("CUSTOMER");
            var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "C");
            var c = new EntitlementContext("MAIN", "C", "request", "a".repeat(64));
            assertThat(proof.verifyConsumerProof(new CustomerIdentityInternalApi.ProofRequest(consumer, c, "mc1"))
                    .customer().userNo()).isEqualTo("C");
            for (String action : new String[]{"register", "get", "verify", "claim"}) {
                int previous = host.getBean(AtomicInteger.class).get();
                assertThat(status(url, action, "{}", "bad")).isEqualTo(401);
                assertThat(host.getBean(AtomicInteger.class).get()).isEqualTo(previous);
                assertThat(status(url, action, "{}", "owner-key")).isEqualTo(400);
                assertThat(status(url, action, "not-json", "owner-key")).isEqualTo(400);
            }
            var json = new ObjectMapper();
            for (var entry : java.util.Map.of("BAD_REQUEST", 400, "FORBIDDEN", 403, "NOT_FOUND", 404,
                    "CONFLICT", 409, "MEMBER_GUEST_UNAVAILABLE", 503).entrySet()) {
                var req = new GuestRegistrationInternalApi.Get(scope, entry.getKey());
                assertThat(status(url, "get", json.writeValueAsString(req), "owner-key")).isEqualTo(entry.getValue());
            }
            assertThatThrownBy(() -> api.get(new GuestRegistrationInternalApi.Get(scope, "OUTAGE")))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(SecurityUtils.currentUser()).isEmpty();
        var outer = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "OUTER");
        DelegatedIdentityContext.call(outer, () -> {
            assertThatThrownBy(() -> DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "CONSUMER", "USR", "C"), () -> {
                        throw new IllegalStateException("failed");
                    })).isInstanceOf(IllegalStateException.class);
            assertThat(DelegatedIdentityContext.current()).isEqualTo(outer); return null;
        });
    }
    private int status(String url, String action, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url
                + GuestRegistrationInternalApi.BASE + action)).header(InternalHttp.TOKEN_HEADER, token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({InternalGuestRegistrationController.class, InternalCustomerIdentityController.class,
            GlobalExceptionHandler.class, ApiResponseWrapper.class})
    static class Host {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean GuestRegistrationPort guests(AtomicInteger calls) {
            return new GuestRegistrationPort() {
                private void current(Scope scope) {
                    calls.incrementAndGet();
                    assertThat(DelegatedIdentityContext.current()).isEqualTo(scope.identity());
                }
                @Override public Registration register(Scope s, RegisterCommand c) {
                    current(s); return new Registration("GUEST", "secret", "REGISTERED", 1);
                }
                @Override public GuestFact get(Scope s, String n) {
                    current(s);
                    if ("OUTAGE".equals(n)) { throw new IllegalStateException("outage"); }
                    if (!"GUEST".equals(n)) { throw BizException.of(ErrorCode.valueOf(n)); }
                    return new GuestFact(n, "REGISTERED", null, 1);
                }
                @Override public GuestFact verify(Scope s, String n, String t) { return get(s, n); }
                @Override public Claim claim(Scope s, String n, ClaimCommand c) {
                    current(s); return new Claim(n, "CUSTOMER", 2);
                }
            };
        }
        @Bean CustomerIdentityPort customers() {
            return new CustomerIdentityPort() {
                @Override public ResolvedCustomer resolveReference(EntitlementContext c, ReferenceQuery q) {
                    return new ResolvedCustomer("CUSTOMER");
                }
                @Override public ResolvedCustomer verifyConsumerProof(EntitlementContext c, String code) {
                    assertThat(SecurityUtils.requireUser().realm().name()).isEqualTo("CONSUMER");
                    assertThat(SecurityUtils.requireUser().userNo()).isEqualTo(c.actorNo());
                    return new ResolvedCustomer(c.actorNo());
                }
            };
        }
    }
}
