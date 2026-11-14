package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.api.biz.BizServiceOperationController;
import ai.neargo.shop.beauty.api.biz.BizAppointmentController;
import ai.neargo.shop.beauty.api.biz.BizAvailabilityController;
import ai.neargo.shop.beauty.api.mp.MpAppointmentController;
import ai.neargo.shop.beauty.api.mp.MpAvailabilityController;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** No security gate protects /mp: each private controller must still require the real consumer namespace. */
class BeautyBookingIngressTest {
    @Test void permitAllPrivateListStillRequiresConsumerUsrAndUsesOnlyCurrentUser() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var appointments = mock(AppointmentService.class);
        beans.registerSingleton("appointments", appointments);
        when(appointments.listMyAppointments(anyString(), any())).thenAnswer(i -> {
            assertThat((String) i.getArgument(0)).isEqualTo("C"); return PageData.empty(1, 20);
        });
        var mvc = MockMvcBuilders.standaloneSetup(new MpAppointmentController(
                beans.getBeanProvider(AppointmentService.class))).setControllerAdvice(new
                        GlobalExceptionHandler()).build();
        mvc.perform(get("/mp/x/beauty/appointments")).andExpect(status().isUnauthorized());
        verifyNoInteractions(appointments);
        for (LoginUser user : List.of(LoginUser.merchant("M", "m"), LoginUser.merchantByUser("B", "b"))) {
            DelegatedIdentityContext.call(identity(user), () -> {
                try { mvc.perform(get("/mp/x/beauty/appointments")).andExpect(jsonPath("$.code").value(10403)); }
                catch (Exception e) { throw new RuntimeException(e); }
                return null;
            });
        }
        verifyNoInteractions(appointments);
        DelegatedIdentityContext.call(identity(LoginUser.consumer("C", "c")), () -> {
            try { mvc.perform(get("/mp/x/beauty/appointments").param("userNo", "VICTIM"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.records").isEmpty()); }
            catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
    }
    @Test void publicAvailabilityRemainsAnonymous() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var availability = mock(AppointmentAvailabilityService.class);
        when(availability.queryPublicAvailability(any())).thenReturn(
                new PublicAvailabilityVO(List.of(), OffsetDateTime.parse("2026-10-22T10:00Z"), false));
        beans.registerSingleton("availability", availability);
        var mvc = MockMvcBuilders.standaloneSetup(new MpAvailabilityController(
                beans.getBeanProvider(AppointmentAvailabilityService.class))).build();
        mvc.perform(post("/mp/x/beauty/availability/queries").contentType("application/json").content(
                "{\"storeNo\":\"S\",\"items\":[{\"skuNo\":\"SKU\",\"qty\":1}],"
                + "\"from\":\"2026-10-22T10:00:00Z\",\"to\":\"2026-10-22T11:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false));
    }
    @Test void rawHeaderCannotExpandTrustedStoreAndGetNeverRecovers() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var reads = mock(ServiceOperationQueryService.class);
        var recovery = mock(ServiceOperationRecoveryService.class);
        var directory = mock(StoreDirectoryPort.class);
        beans.registerSingleton("reads", reads); beans.registerSingleton("recovery", recovery);
        beans.registerSingleton("directory", directory);
        when(directory.cards(any())).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                "S", "store", "E", null, "ACTIVE", true, "", "", null, null, 0, 0)));
        when(reads.getOperation("E", "S", "U", "O")).thenReturn(new OperationVO("O", "A", "PENDING", "CREATE", null));
        var mvc = MockMvcBuilders.standaloneSetup(new BizServiceOperationController(
                beans.getBeanProvider(ServiceOperationQueryService.class),
                beans.getBeanProvider(ServiceOperationRecoveryService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        try {
            DelegatedIdentityContext.call(identity(LoginUser.merchantByUser("U", "u")), () -> {
                try {
                    mvc.perform(get("/biz/x/beauty/operations/O").header("X-Store-No", "FOREIGN"))
                            .andExpect(jsonPath("$.code").value(10403));
                    mvc.perform(get("/biz/x/beauty/operations/O").header("X-Store-No", "S"))
                            .andExpect(jsonPath("$.operationNo").value("O"));
                } catch (Exception e) { throw new RuntimeException(e); }
                return null;
            });
            verifyNoInteractions(recovery);
        } finally { BizContext.clear(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"appointments", "availability", "operations"})
    void everyBookingBizIngressRejectsRealmRawHeaderAndForeignScopeBeforeDelegating(String ingress) {
        var beans = new DefaultListableBeanFactory();
        var appointments = mock(AppointmentService.class);
        var availability = mock(AppointmentAvailabilityService.class);
        var reads = mock(ServiceOperationQueryService.class);
        var recovery = mock(ServiceOperationRecoveryService.class);
        var directory = mock(StoreDirectoryPort.class);
        beans.registerSingleton("appointments", appointments);
        beans.registerSingleton("availability", availability);
        beans.registerSingleton("reads", reads);
        beans.registerSingleton("recovery", recovery);
        beans.registerSingleton("directory", directory);
        var mvc = MockMvcBuilders.standaloneSetup(
                new BizAppointmentController(beans.getBeanProvider(AppointmentService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class)),
                new BizAvailabilityController(beans.getBeanProvider(AppointmentAvailabilityService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class)),
                new BizServiceOperationController(beans.getBeanProvider(ServiceOperationQueryService.class),
                        beans.getBeanProvider(ServiceOperationRecoveryService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        try {
            DelegatedIdentityContext.call(identity(LoginUser.consumer("U", "u")), () -> {
                try { mvc.perform(bookingBizRequest(ingress).header("X-Store-No", "S"))
                        .andExpect(jsonPath("$.code").value(10403)); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                return null;
            });
            verifyNoInteractions(directory, appointments, availability, reads, recovery);
            when(directory.cards(any())).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                    "S", "store", "E", null, "ACTIVE", true, "", "", null, null, 0, 0)));
            DelegatedIdentityContext.call(identity(LoginUser.merchantByUser("U", "u")), () -> {
                try { mvc.perform(bookingBizRequest(ingress).header("X-Store-No", "FOREIGN"))
                        .andExpect(jsonPath("$.code").value(10403)); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                return null;
            });
            verifyNoInteractions(appointments, availability, reads, recovery);
            when(directory.cards(any())).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                    "S", "store", "FOREIGN_ENTITY", null, "ACTIVE", true, "", "", null, null, 0, 0)));
            DelegatedIdentityContext.call(identity(LoginUser.merchant("M", "m")), () -> {
                try { mvc.perform(bookingBizRequest(ingress).header("X-Store-No", "S"))
                        .andExpect(jsonPath("$.code").value(10403)); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                return null;
            });
            verifyNoInteractions(appointments, availability, reads, recovery);
        } finally { BizContext.clear(); }
    }
    private MockHttpServletRequestBuilder bookingBizRequest(String ingress) {
        if (ingress.equals("availability")) {
            return post("/biz/x/beauty/availability/queries").contentType("application/json").content(
                    "{\"storeNo\":\"S\",\"items\":[{\"skuNo\":\"SKU\",\"qty\":1}],"
                    + "\"from\":\"2026-10-22T10:00:00Z\",\"to\":\"2026-10-22T11:00:00Z\"}");
        }
        return get("/biz/x/beauty/" + (ingress.equals("operations") ? "operations/O" : "appointments"));
    }
    private DelegatedIdentity identity(LoginUser u) {
        return new DelegatedIdentity(u.tenantNo(), u.realm().name(), u.subjectKind().name(), u.userNo());
    }
}
