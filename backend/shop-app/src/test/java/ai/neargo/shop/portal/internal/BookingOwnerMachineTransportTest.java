package ai.neargo.shop.portal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
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

/** Actual neutral owner ingress with global HTTP200 public advice; service doubles delimit TCP evidence. */
class BookingOwnerMachineTransportTest {
    @Test void badTokenCannotReachOwnerAndActualIdentityAndExplicitFailuresSurviveAdvice() throws Exception {
        try (var context = new SpringApplicationBuilder(Host.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--server.address=127.0.0.1", "--shop.services.internal-token=owner-key")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String url = "http://127.0.0.1:" + port;
            var http = new InternalHttp(service -> Optional.of(url), "owner-key");
            var api = http.client("PLATFORM", ResourceBookingInternalApi.class, Duration.ofSeconds(2));
            var customers = http.client("PLATFORM", CustomerIdentityInternalApi.class, Duration.ofSeconds(2));
            var calls = context.getBean(AtomicInteger.class);
            var identity = new DelegatedIdentity("MAIN", "MERCHANT", "USR", "U");
            for (String number : List.of("FOREIGN", "CONFLICT", "OUTAGE")) {
                var body = new ResourceBookingInternalApi.Command(identity,
                        new ResourceBookingPort.Actor("U", "MERCHANT", "USR"),
                        new ResourceBookingPort.Scope("MAIN", "E", "S"), number);
                int expected = number.equals("FOREIGN") ? 403 : number.equals("CONFLICT") ? 409 : 503;
                assertThat(status(url, new ObjectMapper().writeValueAsString(body))).isEqualTo(expected);
            }
            assertThat(status(url, "{\"identity\":{\"tenantNo\":\"MAIN\",\"realm\":\"OPERATOR\","
                    + "\"subjectKind\":\"OPS\",\"actorNo\":\"X\"}}")).isEqualTo(400);
            for (LoginUser user : List.of(LoginUser.merchant("M", "m"), LoginUser.merchantByUser("U", "u"),
                    LoginUser.consumer("C", "c"))) {
                var i = new DelegatedIdentity(user.tenantNo(), user.realm().name(), user.subjectKind().name(),
                        user.userNo());
                var actor = new ResourceBookingPort.Actor(i.actorNo(), i.realm(), i.subjectKind());
                var request = new ResourceBookingInternalApi.Command(i, actor,
                        new ResourceBookingPort.Scope("MAIN", "E", "S"), "ABSENT");
                assertThat(api.query(request).found()).isFalse();
                var bad = new InternalHttp(service -> Optional.of(url), "bad")
                        .client("PLATFORM", ResourceBookingInternalApi.class, Duration.ofSeconds(2));
                int previous = calls.get();
                assertThatThrownBy(() -> bad.query(request)).isInstanceOf(RuntimeException.class);
                assertThat(calls.get()).isEqualTo(previous);
                var wrong = new ResourceBookingInternalApi.Command(i,
                        new ResourceBookingPort.Actor("OTHER", i.realm(), i.subjectKind()), request.scope(), "ABSENT");
                assertThatThrownBy(() -> api.query(wrong)).isInstanceOf(RuntimeException.class);
                assertThat(calls.get()).isEqualTo(previous);
                for (String number : List.of("FOREIGN", "OUTAGE", "CONFLICT")) {
                    assertThatThrownBy(() -> api.query(new ResourceBookingInternalApi.Command(i, actor,
                            request.scope(), number))).isInstanceOf(RuntimeException.class);
                }
                if ("MERCHANT".equals(i.realm())) {
                    var c = new EntitlementContext("MAIN", i.actorNo(), "R", "a".repeat(64));
                    var q = new CustomerIdentityPort.ReferenceQuery("E", "S", "TICKET");
                    var result = customers.resolve(new CustomerIdentityInternalApi.Request(i, c, q));
                    assertThat(result.identity()).isEqualTo(i);
                    assertThat(result.context()).isEqualTo(c);
                    assertThat(result.customer().userNo()).isEqualTo("CUSTOMER");
                }
            }
        }
        assertThat(SecurityUtils.currentUser()).isEmpty();
    }
    private int status(String url, String body) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url
                    + ResourceBookingInternalApi.BASE + "query")).header(InternalHttp.TOKEN_HEADER, "owner-key")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({InternalResourceBookingController.class, InternalCustomerIdentityController.class,
            GlobalExceptionHandler.class, ApiResponseWrapper.class})
    static class Host {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean ResourceCandidatesPort candidates() { return mock(ResourceCandidatesPort.class); }
        @Bean ResourceBookingPort booking(AtomicInteger calls) {
            var port = mock(ResourceBookingPort.class);
            when(port.query(any(), any(), any())).thenAnswer(invocation -> {
                ResourceBookingPort.Actor actor = invocation.getArgument(0);
                String number = invocation.getArgument(2);
                calls.incrementAndGet();
                var u = SecurityUtils.requireUser();
                assertThat(u.userNo()).isEqualTo(actor.operatorNo());
                assertThat(u.realm().name()).isEqualTo(actor.realm());
                assertThat(u.subjectKind().name()).isEqualTo(actor.subjectKind());
                switch (number) {
                    case "FOREIGN" -> throw BizException.of(ErrorCode.FORBIDDEN);
                    case "OUTAGE" -> throw new IllegalStateException("database down");
                    case "CONFLICT" -> throw BizException.of(ErrorCode.CONFLICT);
                    default -> { return Optional.empty(); }
                }
            });
            return port;
        }
        @Bean CustomerIdentityPort customers(AtomicInteger calls) {
            return (context, query) -> {
                calls.incrementAndGet();
                assertThat(SecurityUtils.requireUser().userNo()).isEqualTo(context.actorNo());
                if (!"E".equals(query.entityNo()) || !"S".equals(query.storeNo())) {
                    throw BizException.of(ErrorCode.FORBIDDEN);
                }
                return new CustomerIdentityPort.ResolvedCustomer("CUSTOMER");
            };
        }
    }
}
