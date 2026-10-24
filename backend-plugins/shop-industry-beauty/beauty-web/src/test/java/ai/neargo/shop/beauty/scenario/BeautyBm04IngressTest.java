package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.api.biz.BizAppointmentController;
import ai.neargo.shop.beauty.api.biz.BizArrivalChallengeController;
import ai.neargo.shop.beauty.api.biz.BizBusinessTraceController;
import ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController;
import ai.neargo.shop.beauty.api.biz.BizReceptionController;
import ai.neargo.shop.beauty.api.biz.BizServiceOperationController;
import ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController;
import ai.neargo.shop.beauty.api.biz.BizWorkOrderController;
import ai.neargo.shop.beauty.api.mp.MpReceptionController;
import ai.neargo.shop.beauty.api.mp.MpWorkOrderController;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.CheckInProof;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.CustomerIdentityVO;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.ReceptionIdentityProof;
import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Public boundaries delegate only authenticated scope and strict request shapes. */
class BeautyBm04IngressTest {
    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void deliveredMethodsDelegateExactlyAndUnsupportedRoutesAreAbsent() {
        var beans = new DefaultListableBeanFactory();
        var service = mock(AppointmentService.class);
        var directory = mock(StoreDirectoryPort.class);
        beans.registerSingleton("service", service);
        beans.registerSingleton("directory", directory);
        when(directory.cards(any()))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S", "store", "E", null, "ACTIVE", true, "", "", null, null,
                                        0, 0)));
        when(service.openWorkOrderFromAppointment(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WorkOrderVO(
                                "W",
                                "A",
                                1,
                                "OPEN",
                                null,
                                "NONE",
                                "O",
                                "DONE",
                                List.of("T"),
                                null));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new BizAppointmentController(
                                        beans.getBeanProvider(AppointmentService.class),
                                        beans.getBeanProvider(StoreDirectoryPort.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "U"),
                () -> {
                    try {
                        mvc.perform(
                                        post("/biz/x/beauty/appointments/A/work-orders")
                                                .header("X-Store-No", "S")
                                                .header("Idempotency-Key", "key")
                                                .contentType("application/json")
                                                .content(
                                                        "{\"expectedRevision\":1,\"assignments\":[]}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workOrderNo").value("W"));
                        mvc.perform(post("/biz/x/beauty/work-orders/W/start"))
                                .andExpect(status().isNotFound());
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return null;
                });
        verify(service)
                .openWorkOrderFromAppointment(eq("E"), eq("S"), eq("U"), eq("A"), any(), eq("key"));
    }

    @Test
    void unknownFieldsMissingRevisionsAndPathBodyAliasesFailBeforeEffects() {
        var json = JsonMapper.builder().build();
        for (String quantity : List.of("1.2", "\"1\"", "null", "0")) {
            String body = "{\"skuNo\":\"SKU\",\"qty\":" + quantity + "}";
            assertThatThrownBy(() -> json.readValue(body, AppointmentItemReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void strictCreatorsRejectMissingNullRevisionsAndUnknownNestedAssertions() {
        var json = JsonMapper.builder().build();
        assertThatThrownBy(
                        () -> json.readValue("{\"reason\":\"repair\"}", RecoverSubmissionReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"reason\":\"repair\",\"expectedRevision\":null}",
                                        RecoverSubmissionReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"itemIndex\":0,\"requirementNo\":\"R\",\"resourceNo\":\"RES\","
                                            + "\"actorNo\":\"spoof\"}",
                                        AppointmentAssignmentReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"expectedRevision\":1,\"appointmentNo\":\"different\"}",
                                        OpenAppointmentWorkOrderReq.class))
                .isInstanceOf(RuntimeException.class);
    }

    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-20T10:00:00Z");

    record WireCase(
            String method,
            String endpoint,
            String action,
            String realm,
            String target,
            Object request) {}

    record Harness(
            MockMvc mvc, AtomicInteger calls, List<String> invoked, StoreDirectoryPort directory) {}

    private static SubmitServicesReq submit() {
        return new SubmitServicesReq(
                0,
                1,
                List.of(new SubmitServicesReq.TaskSelection("T", 0, 0)),
                List.of(),
                new SubmitServicesReq.SourceSelection(
                        "PREPARE",
                        "cr1_" + "c".repeat(43),
                        "ONLINE",
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()));
    }

    private static List<WireCase> cases() {
        return List.of(
                new WireCase(
                        "registerGuest",
                        "BAPI-031",
                        "REGISTER_GUEST",
                        "MERCHANT",
                        null,
                        new BeautyReceptionInternalApi.RegisterGuest(
                                new RegisterGuestReq("contact", "name", true), "key")),
                new WireCase(
                        "claimGuest",
                        "BAPI-032",
                        "CLAIM_GUEST",
                        "MERCHANT",
                        "G",
                        new BeautyReceptionInternalApi.ClaimGuest(
                                "G",
                                new ClaimGuestReq("cr1_" + "c".repeat(43), "k".repeat(43)),
                                "key")),
                new WireCase(
                        "checkIn",
                        "BAPI-047",
                        "CHECK_IN",
                        "MERCHANT",
                        "A",
                        new BeautyReceptionInternalApi.CheckIn(
                                new CheckInReq(
                                        "A",
                                        new ReceptionIdentityProof(
                                                "cr1_" + "c".repeat(43), null, null),
                                        1L),
                                "key")),
                new WireCase(
                        "receiveWalkIn",
                        "BAPI-048",
                        "RECEIVE_WALK_IN",
                        "MERCHANT",
                        null,
                        new BeautyReceptionInternalApi.ReceiveWalkIn(
                                new ReceiveWalkInReq(
                                        "S",
                                        null,
                                        "G",
                                        List.of(new AppointmentItemReq("SKU", 1)),
                                        List.of(),
                                        null),
                                "key")),
                new WireCase(
                        "coordinateReceptionException",
                        "BAPI-053",
                        "COORDINATE_EXCEPTION",
                        "MERCHANT",
                        "A",
                        new BeautyReceptionInternalApi.CoordinateException(
                                new ReceptionExceptionReq(
                                        "A", "LATE", "KEEP_WAITING", "reason", 1L),
                                "key")),
                new WireCase(
                        "issueArrivalChallenge",
                        "BM04-ARRIVAL-CHALLENGE",
                        "ISSUE_ARRIVAL_CHALLENGE",
                        "MERCHANT",
                        null,
                        new BeautyReceptionInternalApi.IssueArrivalChallenge("key")),
                new WireCase(
                        "openWorkOrderFromAppointment",
                        "BAPI-052",
                        "OPEN_APPOINTMENT",
                        "MERCHANT",
                        "A",
                        new BeautyReceptionInternalApi.OpenAppointment(
                                "A", new OpenAppointmentWorkOrderReq(1L, List.of(), null), "key")),
                new WireCase(
                        "openWorkOrder",
                        "BAPI-054",
                        "OPEN_WORK_ORDER",
                        "MERCHANT",
                        null,
                        new BeautyReceptionInternalApi.OpenWorkOrder(
                                new OpenWorkOrderReq(
                                        null,
                                        "S",
                                        List.of(new AppointmentItemReq("SKU", 1)),
                                        null,
                                        "G",
                                        null,
                                        List.of(),
                                        null),
                                "key")),
                new WireCase(
                        "getWorkOrder",
                        "BAPI-055",
                        "GET_WORK_ORDER",
                        "MERCHANT",
                        "W",
                        new BeautyReceptionInternalApi.WorkOrderRef("W")),
                new WireCase(
                        "getMyWorkOrder",
                        "BAPI-155",
                        "GET_MY_WORK_ORDER",
                        "CONSUMER",
                        "W",
                        new BeautyReceptionInternalApi.WorkOrderRef("W")),
                new WireCase(
                        "checkInMyAppointment",
                        "BAPI-154",
                        "CHECK_IN_MY_APPOINTMENT",
                        "CONSUMER",
                        "A",
                        new BeautyReceptionInternalApi.CheckInMine(
                                new MyCheckInReq(
                                        "A",
                                        new CheckInProof("mc1_" + "m".repeat(43), "k".repeat(43)),
                                        1L),
                                "key")),
                new WireCase(
                        "submitServices",
                        "BAPI-056",
                        "SUBMIT_SERVICES",
                        "MERCHANT",
                        "W",
                        new BeautyReceptionInternalApi.SubmitServices("W", submit(), "key")),
                new WireCase(
                        "getBill",
                        "BAPI-071",
                        "GET_BILL",
                        "MERCHANT",
                        "W",
                        new BeautyReceptionInternalApi.WorkOrderRef("W")),
                new WireCase(
                        "getSubmission",
                        "BAPI-072",
                        "GET_SUBMISSION",
                        "MERCHANT",
                        "SUB",
                        new BeautyReceptionInternalApi.SubmissionRef("SUB")),
                new WireCase(
                        "requestSubmissionRecovery",
                        "BAPI-073",
                        "REQUEST_SUBMISSION_RECOVERY",
                        "MERCHANT",
                        "SUB",
                        new BeautyReceptionInternalApi.RequestSubmissionRecovery(
                                "SUB", new RecoverSubmissionReq(1, "reason"), "key")),
                new WireCase(
                        "recoverPending",
                        "BAPI-073",
                        "RECOVER_PENDING",
                        "MERCHANT",
                        "SUB",
                        new BeautyReceptionInternalApi.SubmissionRef("SUB")),
                new WireCase(
                        "getTrace",
                        "BAPI-137",
                        "GET_TRACE",
                        "MERCHANT",
                        "W",
                        new BeautyReceptionInternalApi.BusinessRef("W")),
                new WireCase(
                        "getOperation",
                        "BAPI-160",
                        "GET_OPERATION",
                        "MERCHANT",
                        "O",
                        new BeautyReceptionInternalApi.OperationRef("O")),
                new WireCase(
                        "recoverOperation",
                        "BM03-OPERATION-RECOVERY",
                        "RECOVER_OPERATION",
                        "MERCHANT",
                        "O",
                        new BeautyReceptionInternalApi.OperationRef("O")));
    }

    private static WorkOrderTaskVO task(String number) {
        return new WorkOrderTaskVO(
                number,
                null,
                1,
                "SKU",
                "D",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "COUNT",
                "WAITING",
                0,
                "UNASSIGNED",
                "UNBOUND",
                false,
                null,
                null,
                null,
                List.of(),
                new WorkOrderTaskSnapshotVO(
                        1,
                        "G",
                        "SKU",
                        "D",
                        "a".repeat(64),
                        60,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        List.of()),
                List.of(),
                List.of(),
                List.of("READ"));
    }

    private static WorkOrderVO workOrder() {
        return new WorkOrderVO(
                "W", "A", 1, "OPEN", null, "UNBOUND", "O", "SUCCEEDED", List.of("T"), TIME);
    }

    private static WorkOrderBillVO bill() {
        var observation =
                new SourceObservationVO(
                        "UNKNOWN",
                        1,
                        null,
                        null,
                        1L,
                        "a".repeat(64),
                        Instant.parse("2026-10-20T10:00:00Z"),
                        "DEPENDENCY_UNAVAILABLE",
                        false);
        var source =
                new SourceBillVO(
                        "CO",
                        "ORDER",
                        "SO",
                        "CNY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "WHOLE_SOURCE",
                        List.of(),
                        List.of(),
                        observation,
                        false,
                        List.of("UNKNOWN"));
        return new WorkOrderBillVO(
                "W",
                1,
                "mark",
                TIME.toInstant(),
                List.of(source),
                List.of(),
                false,
                List.of("UNKNOWN"));
    }

    private static ServiceSubmissionVO submission() {
        return new ServiceSubmissionVO(
                "SUB",
                "W",
                "SUBMIT_SOURCE",
                1,
                1,
                1,
                "PENDING",
                "PREPARING",
                "CO",
                null,
                null,
                null,
                null,
                List.of(),
                "RETRY",
                List.of());
    }

    private static Object result(Class<?> type) {
        return switch (type.getSimpleName()) {
            case "GuestRegistrationVO" ->
                    new GuestRegistrationVO(
                            null, "G", "k".repeat(43), "REGISTERED", 1L, "SUCCEEDED", null);
            case "GuestClaimVO" -> new GuestClaimVO("O", "G", "U", 2L, "SUCCEEDED", null);
            case "ArrivalVO" ->
                    new ArrivalVO(
                            "A",
                            2,
                            "CHECKED_IN",
                            TIME,
                            "ON_TIME",
                            TIME,
                            TIME.plusHours(1),
                            "O",
                            "SUCCEEDED");
            case "MyArrivalVO" ->
                    new MyArrivalVO(
                            "A",
                            2,
                            "CHECKED_IN",
                            TIME,
                            "ON_TIME",
                            TIME,
                            TIME.plusHours(1),
                            "O",
                            "SUCCEEDED");
            case "ArrivalChallengeVO" ->
                    new ArrivalChallengeVO("CH", "S", "k".repeat(43), TIME, TIME.plusSeconds(90));
            case "WalkInVO" -> new WalkInVO("O", "SUCCEEDED", "A", workOrder(), TIME, null);
            case "ReceptionExceptionVO" ->
                    new ReceptionExceptionVO("R", "A", "PENDING_REVIEW", List.of(), "O");
            case "WorkOrderVO" -> workOrder();
            case "WorkOrderDetailVO" ->
                    new WorkOrderDetailVO(
                            workOrder(),
                            new CustomerIdentityVO("USER", "U"),
                            1,
                            List.of(task("T")),
                            List.of());
            case "MyWorkOrderVO" ->
                    new MyWorkOrderVO(workOrder(), 1, List.of(task("T")), List.of());
            case "ServiceSubmissionVO" -> submission();
            case "WorkOrderBillVO" -> bill();
            case "RecoveryRequestVO" ->
                    new RecoveryRequestVO("R", "SUB", "O", 1, "ACCEPTED", "RETRY");
            case "BusinessTraceVO" ->
                    new BusinessTraceVO(
                            "W",
                            "W",
                            "A",
                            1,
                            List.of(submission()),
                            bill(),
                            false,
                            List.of("UNKNOWN"));
            case "OperationVO" ->
                    new OperationVO("O", null, "PENDING", "SUBMIT_SOURCE", "RETRY", "W", null);
            default -> throw new AssertionError("unexpected service method");
        };
    }

    private Harness harness() {
        var beans = new DefaultListableBeanFactory();
        var calls = new AtomicInteger();
        var invoked = new ArrayList<String>();
        Answer<Object> answer =
                invocation -> {
                    calls.incrementAndGet();
                    invoked.add(invocation.getMethod().getName());
                    var user = SecurityUtils.requireUser();
                    assertThat(user.userNo()).isEqualTo("actor");
                    if ("MERCHANT".equals(user.realm().name())) {
                        assertThat(invocation.getArgument(0, String.class)).isEqualTo("E");
                        assertThat(invocation.getArgument(1, String.class)).isEqualTo("S");
                        assertThat(invocation.getArgument(2, String.class)).isEqualTo("actor");
                    } else {
                        assertThat(invocation.getArgument(0, String.class)).isEqualTo("actor");
                    }
                    return result(invocation.getMethod().getReturnType());
                };
        beans.registerSingleton("AppointmentService", mock(AppointmentService.class, answer));
        beans.registerSingleton(
                "GuestRegistrationService", mock(GuestRegistrationService.class, answer));
        beans.registerSingleton("ReceptionService", mock(ReceptionService.class, answer));
        beans.registerSingleton(
                "ServiceOperationQueryService", mock(ServiceOperationQueryService.class, answer));
        beans.registerSingleton(
                "ServiceOperationRecoveryService",
                mock(ServiceOperationRecoveryService.class, answer));
        beans.registerSingleton(
                "ServiceSubmissionService", mock(ServiceSubmissionService.class, answer));
        beans.registerSingleton("WorkOrderBillService", mock(WorkOrderBillService.class, answer));
        beans.registerSingleton("WorkOrderService", mock(WorkOrderService.class, answer));
        var directory = mock(StoreDirectoryPort.class);
        when(directory.cards(any()))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S", "store", "E", null, "ACTIVE", true, "", "", null, null,
                                        0, 0)));
        beans.registerSingleton("directory", directory);
        var directoryProvider = beans.getBeanProvider(StoreDirectoryPort.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new BizArrivalChallengeController(
                                        beans.getBeanProvider(ReceptionService.class),
                                        directoryProvider),
                                new BizBusinessTraceController(
                                        beans.getBeanProvider(WorkOrderBillService.class),
                                        directoryProvider),
                                new BizGuestRegistrationController(
                                        beans.getBeanProvider(GuestRegistrationService.class),
                                        directoryProvider),
                                new BizReceptionController(
                                        beans.getBeanProvider(ReceptionService.class),
                                        directoryProvider),
                                new BizServiceSubmissionController(
                                        beans.getBeanProvider(ServiceSubmissionService.class),
                                        directoryProvider),
                                new BizWorkOrderController(
                                        beans.getBeanProvider(WorkOrderService.class),
                                        directoryProvider),
                                new MpReceptionController(
                                        beans.getBeanProvider(ReceptionService.class)),
                                new MpWorkOrderController(
                                        beans.getBeanProvider(WorkOrderService.class)),
                                new BizAppointmentController(
                                        beans.getBeanProvider(AppointmentService.class),
                                        directoryProvider),
                                new BizServiceOperationController(
                                        beans.getBeanProvider(ServiceOperationQueryService.class),
                                        beans.getBeanProvider(
                                                ServiceOperationRecoveryService.class),
                                        directoryProvider))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        return new Harness(mvc, calls, invoked, directory);
    }

    private MockHttpServletRequestBuilder request(WireCase item) {
        return request(item, null, false);
    }

    private MockHttpServletRequestBuilder request(WireCase item, String resource, boolean encoded) {
        String route =
                switch (item.method()) {
                    case "registerGuest" -> "/biz/x/beauty/guest-registrations";
                    case "claimGuest" -> "/biz/x/beauty/guest-registrations/G/claims";
                    case "checkIn" -> "/biz/x/beauty/arrivals";
                    case "receiveWalkIn" -> "/biz/x/beauty/walk-ins";
                    case "coordinateReceptionException" -> "/biz/x/beauty/reception-exceptions";
                    case "issueArrivalChallenge" -> "/biz/x/beauty/arrival-challenges";
                    case "openWorkOrder" -> "/biz/x/beauty/work-orders";
                    case "getWorkOrder" -> "/biz/x/beauty/work-orders/W";
                    case "getMyWorkOrder" -> "/mp/x/beauty/work-orders/W";
                    case "checkInMyAppointment" -> "/mp/x/beauty/arrivals";
                    case "submitServices" -> "/biz/x/beauty/work-orders/W/service-submissions";
                    case "getBill" -> "/biz/x/beauty/work-orders/W/bill";
                    case "getSubmission" -> "/biz/x/beauty/service-submissions/SUB";
                    case "requestSubmissionRecovery" ->
                            "/biz/x/beauty/service-submissions/SUB/recovery-requests";
                    case "getTrace" -> "/biz/x/beauty/traces/W";
                    case "openWorkOrderFromAppointment" ->
                            "/biz/x/beauty/appointments/A/work-orders";
                    case "getOperation" -> "/biz/x/beauty/operations/O";
                    case "recoverOperation" -> "/biz/x/beauty/operations/O/recoveries";
                    default -> throw new AssertionError("not a public route");
                };
        if (resource != null) {
            route = route.replace("/" + item.target(), "/" + resource);
        }
        var request =
                encoded
                        ? (item.method().startsWith("get")
                                ? MockMvcRequestBuilders.get(URI.create(route))
                                : post(URI.create(route)))
                        : (item.method().startsWith("get")
                                ? MockMvcRequestBuilders.get(route)
                                : post(route));
        request.header("X-Store-No", "S");
        for (var component : item.request().getClass().getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(item.request());
                if (component.getName().equals("req")) {
                    request.contentType("application/json")
                            .content(JsonMapper.builder().build().writeValueAsString(value));
                } else if (component.getName().equals("idempotencyKey")) {
                    request.header("Idempotency-Key", value);
                }
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        return request;
    }

    static Stream<Arguments> publicResourcePaths() {
        var paths =
                Set.of(
                        "claimGuest",
                        "getWorkOrder",
                        "getMyWorkOrder",
                        "submitServices",
                        "getBill",
                        "getSubmission",
                        "requestSubmissionRecovery",
                        "getTrace",
                        "openWorkOrderFromAppointment",
                        "getOperation",
                        "recoverOperation");
        return cases().stream()
                .filter(item -> paths.contains(item.method()))
                .flatMap(
                        item ->
                                Stream.of(
                                        Arguments.of(item, " ", false, false),
                                        Arguments.of(item, "%20%20", true, false),
                                        Arguments.of(item, "x".repeat(33), false, false),
                                        Arguments.of(item, "x".repeat(32), false, true)));
    }

    @ParameterizedTest
    @MethodSource("publicResourcePaths")
    void publicResourcePathsValidateBeforeServiceSelection(
            WireCase item, String resource, boolean encoded, boolean valid) {
        var harness = harness();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", item.realm(), "USR", "actor"),
                () -> {
                    try {
                        var result = harness.mvc().perform(request(item, resource, encoded));
                        if (valid) {
                            result.andExpect(status().isOk())
                                    .andExpect(jsonPath("$.code").doesNotExist());
                        } else {
                            result.andExpect(jsonPath("$.code").value(10400));
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return null;
                });
        assertThat(harness.calls()).as("selected business service calls").hasValue(valid ? 1 : 0);
    }

    @Test
    void allEighteenPublicRoutesDelegateCurrentNamespaceAndKeepBm05Closed() {
        var harness = harness();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        for (WireCase item : cases()) {
            if (item.method().equals("recoverPending")) {
                continue;
            }
            int before = harness.calls().get();
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", item.realm(), "USR", "actor"),
                    () -> {
                        try {
                            var response =
                                    harness.mvc()
                                            .perform(request(item))
                                            .andExpect(status().isOk())
                                            .andReturn();
                            var body =
                                    JsonMapper.builder()
                                            .build()
                                            .readTree(response.getResponse().getContentAsString());
                            assertThat(body.isObject()).isTrue();
                            assertThat(body.path("code").isNumber()).isFalse();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
            assertThat(harness.calls()).hasValue(before + 1);
            assertThat(harness.invoked().getLast()).isEqualTo(item.method());
        }
        assertThat(harness.calls()).hasValue(18);
    }

    @Test
    void everyBizIngressRejectsWrongRealmRawStoreAndBodyScopeBeforeDelegation() {
        var harness = harness();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        for (WireCase item : cases()) {
            if (!item.realm().equals("MERCHANT") || item.method().equals("recoverPending")) {
                continue;
            }
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "CONSUMER", "USR", "actor"),
                    () -> {
                        try {
                            harness.mvc()
                                    .perform(request(item))
                                    .andExpect(jsonPath("$.code").value(10403));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "actor"),
                    () -> {
                        try {
                            var request = request(item);
                            request.with(
                                    value -> {
                                        value.removeHeader("X-Store-No");
                                        value.addHeader("X-Store-No", "FOREIGN");
                                        return value;
                                    });
                            harness.mvc()
                                    .perform(request)
                                    .andExpect(jsonPath("$.code").value(10403));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
        }
        assertThat(harness.calls()).hasValue(0);
    }

    @Test
    void consumerIngressUsesOnlyCurrentIdentityAndRejectsIdentityFields() {
        var harness = harness();
        for (WireCase item : cases()) {
            if (!item.realm().equals("CONSUMER")) {
                continue;
            }
            for (String kind : List.of("MCH", "USR")) {
                DelegatedIdentityContext.call(
                        new DelegatedIdentity("MAIN", "MERCHANT", kind, "actor"),
                        () -> {
                            try {
                                harness.mvc()
                                        .perform(request(item))
                                        .andExpect(jsonPath("$.code").value(10403));
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                            return null;
                        });
            }
        }
        var arrival =
                cases().stream()
                        .filter(item -> item.method().equals("checkInMyAppointment"))
                        .findFirst()
                        .orElseThrow();
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "CONSUMER", "USR", "actor"),
                () -> {
                    for (String injected :
                            List.of("userNo", "actorNo", "buyerUserNo", "storeNo", "tenantNo")) {
                        try {
                            var body =
                                    JsonMapper.builder()
                                            .build()
                                            .valueToTree(
                                                    ((BeautyReceptionInternalApi.CheckInMine)
                                                                    arrival.request())
                                                            .req());
                            ((ObjectNode) body).put(injected, "foreign");
                            harness.mvc()
                                    .perform(request(arrival).content(body.toString()))
                                    .andExpect(jsonPath("$.code").value(10400));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    return null;
                });
        assertThat(harness.calls()).hasValue(0);
    }

    @Test
    void keysPathAssertionsBodyStoresAndForeignDirectoryFailBeforeEffects() {
        var harness = harness();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        for (WireCase item : cases()) {
            if (item.method().equals("recoverPending")) {
                continue;
            }
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", item.realm(), "USR", "actor"),
                    () -> {
                        boolean key =
                                List.of(item.request().getClass().getRecordComponents()).stream()
                                        .anyMatch(
                                                component ->
                                                        component
                                                                .getName()
                                                                .equals("idempotencyKey"));
                        if (key) {
                            for (String bad : List.of("", " ", "k".repeat(129), " key")) {
                                try {
                                    var req =
                                            request(item)
                                                    .with(
                                                            value -> {
                                                                value.removeHeader(
                                                                        "Idempotency-Key");
                                                                if (!bad.isEmpty()) {
                                                                    value.addHeader(
                                                                            "Idempotency-Key", bad);
                                                                }
                                                                return value;
                                                            });
                                    harness.mvc()
                                            .perform(req)
                                            .andExpect(jsonPath("$.code").value(10400));
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                        }
                        for (var component : item.request().getClass().getRecordComponents()) {
                            if (!component.getName().equals("req")) {
                                continue;
                            }
                            try {
                                var body =
                                        (ObjectNode)
                                                JsonMapper.builder()
                                                        .build()
                                                        .valueToTree(
                                                                component
                                                                        .getAccessor()
                                                                        .invoke(item.request()));
                                body.put("buyerUserNo", "foreign");
                                harness.mvc()
                                        .perform(request(item).content(body.toString()))
                                        .andExpect(jsonPath("$.code").value(10400));
                                if (item.method().equals("openWorkOrderFromAppointment")) {
                                    body.remove("buyerUserNo");
                                    body.put("appointmentNo", "foreign");
                                    harness.mvc()
                                            .perform(request(item).content(body.toString()))
                                            .andExpect(jsonPath("$.code").value(10400));
                                }
                                if (item.method().equals("openWorkOrder")
                                        || item.method().equals("receiveWalkIn")) {
                                    body.remove("buyerUserNo");
                                    body.put("storeNo", "foreign");
                                    harness.mvc()
                                            .perform(request(item).content(body.toString()))
                                            .andExpect(jsonPath("$.code").value(10400));
                                }
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        }
                        return null;
                    });
        }
        when(harness.directory().cards(any()))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S", "store", "FOREIGN", null, "ACTIVE", true, "", "", null,
                                        null, 0, 0)));
        for (WireCase item : cases()) {
            if (!item.realm().equals("MERCHANT") || item.method().equals("recoverPending")) {
                continue;
            }
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                    () -> {
                        try {
                            harness.mvc()
                                    .perform(request(item))
                                    .andExpect(jsonPath("$.code").value(10403));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
        }
        assertThat(harness.calls()).hasValue(0);
    }
}
