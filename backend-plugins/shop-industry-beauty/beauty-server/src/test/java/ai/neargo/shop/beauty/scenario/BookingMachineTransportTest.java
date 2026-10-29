package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.client.RemoteAppointmentService;
import ai.neargo.shop.beauty.client.RemoteServiceOperationQueryService;
import ai.neargo.shop.beauty.dto.AppointmentSummaryVO;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl;
import ai.neargo.shop.beauty.port.RemoteAppointmentSourceAuthorityPort;
import ai.neargo.shop.beauty.server.api.InternalBeautyBookingController;
import ai.neargo.shop.beauty.server.api.InternalBeautyOperationController;
import ai.neargo.shop.beauty.server.api.InternalBeautySourceController;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.beauty.spi.BeautySourceInternalApi;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

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
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Tomcat/controller/advice over TCP; service/source test doubles bound protocol evidence only. */
class BookingMachineTransportTest {
    @Test void delegatedNamespacesTokenFailureUnknownAndInfrastructureFailureOverActualTcp() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=machine-key")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String url = "http://127.0.0.1:" + port;
            var http = new InternalHttp(service -> Optional.of(url), "machine-key");
            var operations =
                    new RemoteServiceOperationQueryService(
                            http.client(
                                    "BEAUTY",
                                    BeautyReceptionInternalApi.class,
                                    Duration.ofSeconds(2)),
                            new InternalBindings("machine-key"));
            var source = new RemoteAppointmentSourceAuthorityPort(http.client("BEAUTY",
                    BeautySourceInternalApi.class, Duration.ofSeconds(2)));
            var count = context.getBean(AtomicInteger.class);
            for (LoginUser user :
                    List.of(
                            LoginUser.merchant("M", "m"),
                            LoginUser.merchantByUser("U", "u"),
                            LoginUser.consumer("C", "c"))) {
                DelegatedIdentityContext.call(
                        identity(user),
                        () -> {
                            if ("CONSUMER".equals(user.realm().name())) {
                                var appointments =
                                        new RemoteAppointmentService(
                                                http.client(
                                                        "BEAUTY",
                                                        BeautyBookingInternalApi.class,
                                                        Duration.ofSeconds(2)),
                                                http.client(
                                                        "BEAUTY",
                                                        BeautyReceptionInternalApi.class,
                                                        Duration.ofSeconds(2)),
                                                new InternalBindings("machine-key"));
                                assertThat(
                                                appointments
                                                        .getMyAppointment(user.userNo(), "A")
                                                        .appointment()
                                                        .appointmentNo())
                                        .isEqualTo("A");
                                assertThatThrownBy(
                                                () ->
                                                        appointments.getMyAppointment(
                                                                user.userNo(), "FOREIGN"))
                                        .isInstanceOf(BizException.class);
                            } else {
                                var result = operations.getOperation("E", "S", user.userNo(), "O");
                                assertThat(result.state())
                                        .isEqualTo(
                                                user.realm().name()
                                                        + ":"
                                                        + user.subjectKind().name());
                            }
                            var request = request(user, "A");
                            assertThat(source.query(request).state())
                                    .isEqualTo(ResourceSourceAuthorityPort.State.UNKNOWN);
                            assertThat(source.freeze(request).binding())
                                    .isEqualTo(request.binding());
                            return null;
                        });
            }
            int previous = count.get();
            var bad = new InternalHttp(service -> Optional.of(url), "wrong");
            var denied =
                    new RemoteServiceOperationQueryService(
                            bad.client(
                                    "BEAUTY",
                                    BeautyReceptionInternalApi.class,
                                    Duration.ofSeconds(2)),
                            new InternalBindings("wrong"));
            DelegatedIdentityContext.call(
                    identity(LoginUser.merchant("M", "m")),
                    () -> {
                        assertThatThrownBy(() -> denied.getOperation("E", "S", "M", "O"))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(count.get()).isEqualTo(previous);
                        assertThatThrownBy(() -> operations.getOperation("FOREIGN", "S", "M", "O"))
                                .isInstanceOf(BizException.class)
                                .extracting(e -> ((BizException) e).errorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN);
                        assertThatThrownBy(
                                        () ->
                                                operations.getOperation(
                                                        "E", "S", "M", "WRONG_RESPONSE"))
                                .isInstanceOf(BizException.class);
                        assertThatThrownBy(() -> operations.getOperation("E", "S", "M", "OUTAGE"))
                                .isInstanceOf(RuntimeException.class);
                        assertThatThrownBy(
                                        () -> operations.getOperation("E", "S", "M", "INCOMPLETE"))
                                .isInstanceOf(BizException.class)
                                .extracting(e -> ((BizException) e).errorCode())
                                .isEqualTo(ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
                        assertThatThrownBy(
                                        () ->
                                                source.query(
                                                        request(
                                                                LoginUser.merchant("M", "m"),
                                                                "FOREIGN")))
                                .isInstanceOf(RuntimeException.class);
                        return null;
                    });
            assertThat(SecurityUtils.currentUser()).isEmpty();
        }
    }
    @Test void restoresExactPriorContextAfterExceptionAndSequentialIdentities() {
        var original = SecurityContextHolder.getContext();
        for (LoginUser user :
                List.of(LoginUser.consumer("C", "c"), LoginUser.merchantByUser("B", "b"))) {
            assertThatThrownBy(() -> DelegatedIdentityContext.call(identity(user), () -> {
                assertThat(SecurityUtils.requireUser().realm()).isEqualTo(user.realm());
                assertThat(SecurityUtils.requireUser().subjectKind()).isEqualTo(user.subjectKind());
                throw new IllegalStateException("failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(SecurityContextHolder.getContext()).isSameAs(original);
        }
    }
    private static DelegatedIdentity identity(LoginUser u) {
        return new DelegatedIdentity(u.tenantNo(), u.realm().name(), u.subjectKind().name(), u.userNo());
    }
    private static ResourceSourceAuthorityPort.Request request(LoginUser u, String appointment) {
        var b =
                new ResourceSourceAuthorityPort.Binding(
                        "MAIN",
                        "E",
                        "S",
                        "CMD",
                        "BEAUTY_APPOINTMENT",
                        appointment,
                        0,
                        "RESERVE",
                        "a".repeat(64),
                        "b".repeat(64));
        return new ResourceSourceAuthorityPort.Request(b, u.userNo(), u.realm().name(), u.subjectKind().name());
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class
    })
    @Import({
        InternalBeautyBookingController.class,
        InternalBeautySourceController.class,
        InternalBeautyOperationController.class,
        GlobalExceptionHandler.class,
        ApiResponseWrapper.class
    })
    static class Host {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean AppointmentService appointments(AtomicInteger calls) {
            var service = mock(AppointmentService.class);
            when(service.getMyAppointment(any(), any()))
                    .thenAnswer(
                            invocation -> {
                                String user = invocation.getArgument(0),
                                        number = invocation.getArgument(1);
                                calls.incrementAndGet();
                                assertThat(SecurityUtils.requireUser().userNo()).isEqualTo(user);
                                assertThat(SecurityUtils.requireUser().realm().name())
                                        .isEqualTo("CONSUMER");
                                assertThat(SecurityUtils.requireUser().subjectKind().name())
                                        .isEqualTo("USR");
                                if (!number.equals("A")) {
                                    throw BizException.of(ErrorCode.FORBIDDEN);
                                }
                                return new MyAppointmentDetailVO(
                                        new AppointmentSummaryVO(
                                                "A", "S", 1, "CONFIRMED", null, null, 1L),
                                        List.of(),
                                        List.of(),
                                        List.of());
                            });
            return service;
        }
        @Bean AppointmentAvailabilityService availability() { return mock(AppointmentAvailabilityService.class); }
        @Bean ServiceOperationRecoveryService recovery() { return mock(ServiceOperationRecoveryService.class); }
        @Bean ServiceOperationQueryService operations(AtomicInteger calls) {
            return (entity, store, actor, number) -> {
                calls.incrementAndGet();
                var u = SecurityUtils.requireUser();
                assertThat(u.userNo()).isEqualTo(actor);
                if (!"E".equals(entity) || !"S".equals(store)) {
                    throw BizException.of(ErrorCode.FORBIDDEN);
                }
                if (number.equals("OUTAGE")) {
                    throw new IllegalStateException("actual owner unavailable");
                }
                if (number.equals("INCOMPLETE")) {
                    throw BizException.of(ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
                }
                return new OperationVO(
                        number.equals("WRONG_RESPONSE") ? "OTHER" : number,
                        "A",
                        u.realm().name() + ":" + u.subjectKind().name(),
                        "CREATE",
                        null);
            };
        }
        @Bean AppointmentSourceAuthorityPortImpl source(AtomicInteger calls) {
            var source = mock(AppointmentSourceAuthorityPortImpl.class);
            when(source.query(any())).thenAnswer(invocation -> {
                ResourceSourceAuthorityPort.Request request = invocation.getArgument(0);
                calls.incrementAndGet();
                assertThat(SecurityUtils.requireUser().userNo()).isEqualTo(request.operatorNo());
                if (!"A".equals(request.binding().sourceNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
                return new ResourceSourceAuthorityPort.Decision(request.binding(),
                        ResourceSourceAuthorityPort.State.UNKNOWN, null, null, null, null, null, null);
            });
            when(source.freeze(any())).thenAnswer(invocation -> source.query(invocation.getArgument(0)));
            return source;
        }
    }
}
