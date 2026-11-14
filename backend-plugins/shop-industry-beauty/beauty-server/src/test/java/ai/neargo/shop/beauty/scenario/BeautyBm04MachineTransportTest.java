package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.client.RemoteAppointmentService;
import ai.neargo.shop.beauty.client.RemoteGuestRegistrationService;
import ai.neargo.shop.beauty.client.RemoteReceptionService;
import ai.neargo.shop.beauty.client.RemoteServiceOperationQueryService;
import ai.neargo.shop.beauty.client.RemoteServiceOperationRecoveryService;
import ai.neargo.shop.beauty.client.RemoteServiceSubmissionService;
import ai.neargo.shop.beauty.client.RemoteWorkOrderBillService;
import ai.neargo.shop.beauty.client.RemoteWorkOrderService;
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
import ai.neargo.shop.beauty.server.api.InternalBeautyGuestController;
import ai.neargo.shop.beauty.server.api.InternalBeautyOperationController;
import ai.neargo.shop.beauty.server.api.InternalBeautyReceptionController;
import ai.neargo.shop.beauty.server.api.InternalBeautySubmissionController;
import ai.neargo.shop.beauty.server.api.InternalBeautyTraceController;
import ai.neargo.shop.beauty.server.api.InternalBeautyWorkOrderController;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real TCP/controllers/Advice prove transport only; service doubles do not prove business
 * acceptance.
 */
class BeautyBm04MachineTransportTest {
    private static final String TOKEN = "fixture-only-machine-key";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-20T10:00:00Z");

    record WireCase(
            String method,
            String endpoint,
            String action,
            String realm,
            String target,
            Object request) {}

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

    @Test
    void everyBindingCoordinateAndOriginalPayloadIsVerified() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + port), TOKEN);
            var api =
                    http.client("BEAUTY", BeautyReceptionInternalApi.class, Duration.ofSeconds(3));
            var bindings = new InternalBindings(TOKEN);
            for (WireCase item : cases()) {
                boolean merchant = item.realm().equals("MERCHANT");
                var binding =
                        new InternalRequestBinding(
                                1,
                                item.endpoint(),
                                item.action(),
                                new DelegatedIdentity("MAIN", item.realm(), "USR", "actor"),
                                merchant ? "E" : null,
                                merchant ? "S" : null,
                                item.target());
                var call = new InternalCall<>(binding, item.request());
                assertThatCode(
                                () -> {
                                    Method method =
                                            BeautyReceptionInternalApi.class.getMethod(
                                                    item.method(), InternalCall.class);
                                    var reply = (InternalReply<?>) method.invoke(api, call);
                                    bindings.require(call, reply);
                                    assertThat(reply.result()).isNotNull();
                                })
                        .as(item.method())
                        .doesNotThrowAnyException();
            }
            assertThat(context.getBean(AtomicInteger.class)).hasValue(19);
        }
    }

    @Test
    void remoteServiceFacadesPreserveTypedOwnerResults() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + port), TOKEN);
            var api =
                    http.client("BEAUTY", BeautyReceptionInternalApi.class, Duration.ofSeconds(3));
            var bindings = new InternalBindings(TOKEN);
            Map<String, Object> remotes =
                    Map.ofEntries(
                            Map.entry(
                                    "GuestRegistrationService",
                                    new RemoteGuestRegistrationService(api, bindings)),
                            Map.entry(
                                    "ReceptionService", new RemoteReceptionService(api, bindings)),
                            Map.entry(
                                    "WorkOrderService", new RemoteWorkOrderService(api, bindings)),
                            Map.entry(
                                    "ServiceSubmissionService",
                                    new RemoteServiceSubmissionService(api, bindings)),
                            Map.entry(
                                    "WorkOrderBillService",
                                    new RemoteWorkOrderBillService(api, bindings)),
                            Map.entry(
                                    "AppointmentService",
                                    new RemoteAppointmentService(
                                            mock(BeautyBookingInternalApi.class), api, bindings)),
                            Map.entry(
                                    "ServiceOperationQueryService",
                                    new RemoteServiceOperationQueryService(api, bindings)),
                            Map.entry(
                                    "ServiceOperationRecoveryService",
                                    new RemoteServiceOperationRecoveryService(api, bindings)));
            for (WireCase item : cases()) {
                String service =
                        switch (item.method()) {
                            case "registerGuest", "claimGuest" -> "GuestRegistrationService";
                            case "checkIn",
                                    "receiveWalkIn",
                                    "coordinateReceptionException",
                                    "issueArrivalChallenge",
                                    "checkInMyAppointment" ->
                                    "ReceptionService";
                            case "openWorkOrder",
                                    "getWorkOrder",
                                    "getMyWorkOrder",
                                    "submitServices",
                                    "getBill" ->
                                    "WorkOrderService";
                            case "getSubmission", "requestSubmissionRecovery", "recoverPending" ->
                                    "ServiceSubmissionService";
                            case "getTrace" -> "WorkOrderBillService";
                            case "openWorkOrderFromAppointment" -> "AppointmentService";
                            case "getOperation" -> "ServiceOperationQueryService";
                            case "recoverOperation" -> "ServiceOperationRecoveryService";
                            default -> null;
                        };
                if (service == null) {
                    continue;
                }
                DelegatedIdentityContext.call(
                        new DelegatedIdentity("MAIN", item.realm(), "USR", "actor"),
                        () -> {
                            var args = new ArrayList<Object>();
                            if (item.realm().equals("MERCHANT")) {
                                args.add("E");
                                args.add("S");
                            }
                            args.add("actor");
                            assertThatCode(
                                            () -> {
                                                for (var component :
                                                        item.request()
                                                                .getClass()
                                                                .getRecordComponents()) {
                                                    args.add(
                                                            component
                                                                    .getAccessor()
                                                                    .invoke(item.request()));
                                                }
                                                Object remote = remotes.get(service);
                                                Method method =
                                                        List.of(remote.getClass().getMethods())
                                                                .stream()
                                                                .filter(
                                                                        candidate ->
                                                                                candidate
                                                                                        .getName()
                                                                                        .equals(
                                                                                                item
                                                                                                        .method()))
                                                                .findFirst()
                                                                .orElseThrow();
                                                assertThat(method.invoke(remote, args.toArray()))
                                                        .isNotNull();
                                            })
                                    .as(item.method())
                                    .doesNotThrowAnyException();
                            return null;
                        });
            }
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "actor"),
                    () -> {
                        assertThat(
                                        ((ServiceSubmissionService)
                                                        remotes.get("ServiceSubmissionService"))
                                                .submitForWorkOrder(
                                                        "E", "S", "actor", "W", submit(), "key")
                                                .workOrderNo())
                                .isEqualTo("W");
                        assertThat(
                                        ((WorkOrderBillService) remotes.get("WorkOrderBillService"))
                                                .getBill("E", "S", "actor", "W")
                                                .sources()
                                                .getFirst()
                                                .dueMinor())
                                .isNull();
                        return null;
                    });
            assertThat(context.getBean(AtomicInteger.class)).hasValue(21);
        }
    }

    @Test
    void error200NullMalformedMissingFieldsAndWrongEchoNeverSucceed() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http =
                    new InternalHttp(
                            service -> Optional.of("http://127.0.0.1:" + port + "/internal/fault"),
                            TOKEN);
            var api =
                    http.client("BEAUTY", BeautyReceptionInternalApi.class, Duration.ofSeconds(2));
            var remote = new RemoteGuestRegistrationService(api, new InternalBindings(TOKEN));
            var fault = context.getBean(FaultEndpoint.class);
            for (String mode :
                    List.of(
                            "error200",
                            "null",
                            "empty",
                            "malformed",
                            "missingNullable",
                            "missingRevision",
                            "wrongDigest",
                            "wrongActor",
                            "wrongEndpoint",
                            "wrongAction",
                            "wrongEntity",
                            "wrongStore",
                            "wrongKind",
                            "wrongRealm",
                            "wrongTarget",
                            "wrongTenant",
                            "unbound404",
                            "statusMismatch",
                            "digestMismatch",
                            "unknownCode",
                            "dependency")) {
                fault.mode.set(mode);
                assertFailure(remote, ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, mode);
            }
            for (var entry :
                    Map.of(
                                    "bound404",
                                    ErrorCode.NOT_FOUND,
                                    "bound403",
                                    ErrorCode.FORBIDDEN,
                                    "bound409",
                                    ErrorCode.CONFLICT)
                            .entrySet()) {
                fault.mode.set(entry.getKey());
                assertFailure(remote, entry.getValue(), entry.getKey());
            }
            assertThat(context.getBean(AtomicInteger.class)).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MERCHANT", "CONSUMER"})
    void boundDetailsRejectMissingForeignDuplicateAndReorderedTasks(String realm) {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http =
                    new InternalHttp(
                            service -> Optional.of("http://127.0.0.1:" + port + "/internal/fault"),
                            TOKEN);
            var remote =
                    new RemoteWorkOrderService(
                            http.client(
                                    "BEAUTY",
                                    BeautyReceptionInternalApi.class,
                                    Duration.ofSeconds(2)),
                            new InternalBindings(TOKEN));
            var fault = context.getBean(FaultEndpoint.class);
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", realm, "USR", "actor"),
                    () -> {
                        fault.mode.set("valid");
                        var valid =
                                realm.equals("MERCHANT")
                                        ? remote.getWorkOrder("E", "S", "actor", "W").tasks()
                                        : remote.getMyWorkOrder("actor", "W").tasks();
                        assertThat(valid)
                                .extracting(WorkOrderTaskVO::taskNo)
                                .containsExactly("T", "T2");
                        assertAll(
                                List.of(
                                                "emptyTasks",
                                                "missingTask",
                                                "missingTasksField",
                                                "foreignTask",
                                                "duplicateTask",
                                                "extraTask",
                                                "reorderedTasks")
                                        .stream()
                                        .map(
                                                mode ->
                                                        () -> {
                                                            fault.mode.set(mode);
                                                            assertThatThrownBy(
                                                                            () -> {
                                                                                if (realm.equals(
                                                                                        "MERCHANT"))
                                                                                    remote
                                                                                            .getWorkOrder(
                                                                                                    "E",
                                                                                                    "S",
                                                                                                    "actor",
                                                                                                    "W");
                                                                                else
                                                                                    remote
                                                                                            .getMyWorkOrder(
                                                                                                    "actor",
                                                                                                    "W");
                                                                            })
                                                                    .as(realm + " " + mode)
                                                                    .isInstanceOfSatisfying(
                                                                            BizException.class,
                                                                            BeautyBm04MachineTransportTest
                                                                                    ::assertUnavailable);
                                                        }));
                        return null;
                    });
            assertThat(fault.requests).hasValue(8);
            assertThat(context.getBean(AtomicInteger.class)).hasValue(0);
        }
    }

    @Test
    void blankConfiguredSecretFailsClosedWithoutExposingConfigurationException() {
        var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:1"), "");
        var remote =
                new RemoteGuestRegistrationService(
                        http.client(
                                "BEAUTY", BeautyReceptionInternalApi.class, Duration.ofMillis(100)),
                        new InternalBindings(""));
        assertFailure(remote, ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "blank configuration");
    }

    private static void assertUnavailable(BizException failure) {
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }

    private static void assertFailure(
            RemoteGuestRegistrationService remote, ErrorCode expected, String label) {
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                () -> {
                    assertThatThrownBy(
                                    () ->
                                            remote.registerGuest(
                                                    "E",
                                                    "S",
                                                    "actor",
                                                    new RegisterGuestReq("contact", "name", true),
                                                    "private-key"))
                            .as(label)
                            .isInstanceOfSatisfying(
                                    BizException.class,
                                    failure -> assertThat(failure.errorCode()).isEqualTo(expected));
                    return null;
                });
    }

    @Test
    void malformedCallsWrongTokenAndRouteRejectBeforeAnyOwnerEffect() throws Exception {
        try (var context =
                        new SpringApplicationBuilder(Host.class)
                                .web(WebApplicationType.SERVLET)
                                .profiles("api")
                                .run(
                                        "--server.port=0",
                                        "--server.address=127.0.0.1",
                                        "--shop.beauty.enabled=true",
                                        "--shop.services.internal-token=" + TOKEN);
                var client = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var json = JsonMapper.builder().build();
            var sample = cases().get(1);
            var binding =
                    new InternalRequestBinding(
                            1,
                            sample.endpoint(),
                            sample.action(),
                            new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                            "E",
                            "S",
                            "G");
            var valid =
                    (ObjectNode)
                            json.readTree(
                                    json.writeValueAsString(
                                            new InternalCall<>(binding, sample.request())));
            var invalid = new ArrayList<String>(List.of("null", "", "{}"));
            for (String field : List.of("binding", "request")) {
                invalid.add(valid.deepCopy().putNull(field).toString());
                var missing = valid.deepCopy();
                missing.remove(field);
                invalid.add(missing.toString());
            }
            for (String body : invalid) {
                for (String token : List.of(TOKEN, "wrong")) {
                    assertThat(post(client, port, sample.method(), token, body).statusCode())
                            .isEqualTo(400);
                }
            }
            assertThat(post(client, port, sample.method(), "wrong", valid.toString()).statusCode())
                    .isEqualTo(401);
            var route = valid.deepCopy();
            ((ObjectNode) route.get("binding")).put("action", "GET_OPERATION");
            assertThat(post(client, port, sample.method(), TOKEN, route.toString()).statusCode())
                    .isEqualTo(400);
            var target = valid.deepCopy();
            ((ObjectNode) target.get("binding")).put("targetNo", "other");
            assertThat(post(client, port, sample.method(), TOKEN, target.toString()).statusCode())
                    .isEqualTo(400);
            assertThat(context.getBean(AtomicInteger.class)).hasValue(0);
        }
    }

    private static HttpResponse<String> post(
            HttpClient client, int port, String method, String token, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/internal/beauty/reception/"
                                                + method))
                        .header("Content-Type", "application/json")
                        .header(InternalHttp.TOKEN_HEADER, token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void contextsRestoreAcrossNestedCallsFailuresTimeoutAndDisconnect() throws Exception {
        var previousDataScope = DataScopeContext.current();
        var scopedData = DataScopeSpec.of("STORE", Set.of("bm04-store"));
        DataScopeContext.set(scopedData);
        try {
            var original = SecurityContextHolder.getContext();
            var biz = BizContext.current();
            var meta = new RequestMetaContext.Meta("127.0.0.1", "TEST", "fixture");
            RequestMetaContext.set(meta);
            try (var context =
                    new SpringApplicationBuilder(Host.class)
                            .web(WebApplicationType.SERVLET)
                            .profiles("api")
                            .run(
                                    "--server.port=0",
                                    "--server.address=127.0.0.1",
                                    "--shop.beauty.enabled=true",
                                    "--shop.services.internal-token=" + TOKEN)) {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                var http =
                        new InternalHttp(
                                service ->
                                        Optional.of("http://127.0.0.1:" + port + "/internal/fault"),
                                TOKEN);
                var api =
                        http.client(
                                "BEAUTY", BeautyReceptionInternalApi.class, Duration.ofMillis(80));
                var bindings = new InternalBindings(TOKEN);
                var remote = new RemoteGuestRegistrationService(api, bindings);
                context.getBean(FaultEndpoint.class).mode.set("timeout");
                for (String kind : List.of("MCH", "USR")) {
                    DelegatedIdentityContext.call(
                            new DelegatedIdentity("MAIN", "MERCHANT", kind, "actor"),
                            () -> {
                                var nested = SecurityContextHolder.getContext();
                                assertFailure(
                                        remote, ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "timeout");
                                assertThat(SecurityContextHolder.getContext()).isSameAs(nested);
                                DelegatedIdentityContext.call(
                                        new DelegatedIdentity("MAIN", "CONSUMER", "USR", "actor"),
                                        () -> {
                                            assertThat(
                                                            SecurityContextHolder.getContext()
                                                                    .getAuthentication()
                                                                    .getDetails())
                                                    .isNull();
                                            assertThatThrownBy(
                                                            () ->
                                                                    new RemoteWorkOrderService(
                                                                                    api, bindings)
                                                                            .getMyWorkOrder(
                                                                                    "actor", "W"))
                                                    .isInstanceOfSatisfying(
                                                            BizException.class,
                                                            BeautyBm04MachineTransportTest::assertUnavailable);
                                            return null;
                                        });
                                assertThat(SecurityContextHolder.getContext()).isSameAs(nested);
                                return null;
                            });
                    assertThat(SecurityContextHolder.getContext()).isSameAs(original);
                    assertThat(BizContext.current()).isSameAs(biz);
                    assertThat(DataScopeContext.current()).isSameAs(scopedData);
                    assertThat(RequestMetaContext.current()).isSameAs(meta);
                }
                int before = context.getBean(FaultEndpoint.class).requests.get();
                DelegatedIdentityContext.call(
                        new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                        () -> {
                            TransactionSynchronizationManager.setActualTransactionActive(true);
                            try {
                                assertThatThrownBy(
                                                () ->
                                                        remote.registerGuest(
                                                                "E",
                                                                "S",
                                                                "actor",
                                                                new RegisterGuestReq(
                                                                        "contact", "name", true),
                                                                "key"))
                                        .isInstanceOf(IllegalStateException.class);
                            } finally {
                                TransactionSynchronizationManager.setActualTransactionActive(false);
                            }
                            return null;
                        });
                assertThat(context.getBean(FaultEndpoint.class).requests).hasValue(before);
            } finally {
                RequestMetaContext.clear();
            }
            try (var listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
                var disconnect =
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try (var socket = listener.accept()) {
                                                socket.getInputStream().read();
                                            } catch (IOException failure) {
                                                throw new AssertionError(failure);
                                            }
                                        });
                var http =
                        new InternalHttp(
                                service ->
                                        Optional.of("http://127.0.0.1:" + listener.getLocalPort()),
                                TOKEN);
                var remote =
                        new RemoteGuestRegistrationService(
                                http.client(
                                        "BEAUTY",
                                        BeautyReceptionInternalApi.class,
                                        Duration.ofMillis(150)),
                                new InternalBindings(TOKEN));
                assertFailure(remote, ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "disconnect");
                disconnect.join(2000);
                assertThat(disconnect.isAlive()).isFalse();
                assertThat(SecurityContextHolder.getContext()).isSameAs(original);
            }
            assertThat(DataScopeContext.current()).isSameAs(scopedData);
        } finally {
            DataScopeContext.set(previousDataScope);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void proofsStayTransientAndAuthorizedIssuanceIsPreserved(CapturedOutput output)
            throws Exception {
        try (var context =
                        new SpringApplicationBuilder(Host.class)
                                .web(WebApplicationType.SERVLET)
                                .profiles("api")
                                .run(
                                        "--server.port=0",
                                        "--server.address=127.0.0.1",
                                        "--shop.beauty.enabled=true",
                                        "--shop.services.internal-token=" + TOKEN);
                var client = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var json = JsonMapper.builder().build();
            var request =
                    new BeautyReceptionInternalApi.RegisterGuest(
                            new RegisterGuestReq("private-contact-123", "private-name", true),
                            "private-key-123");
            var call =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    "BAPI-031",
                                    "REGISTER_GUEST",
                                    new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                                    "E",
                                    "S",
                                    null),
                            request);
            var response =
                    post(client, port, "registerGuest", TOKEN, json.writeValueAsString(call));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .doesNotContain(
                            "private-contact", "private-name", "private-key", "cr1_", "mc1_");
            assertThat(json.readTree(response.body()).get("result").get("claimToken").asString())
                    .isEqualTo("k".repeat(43));
            assertThat(call.toString())
                    .doesNotContain("private-contact", "private-name", "private-key");
            assertThat(request.toString())
                    .doesNotContain("private-contact", "private-name", "private-key");
            assertThat(result(GuestRegistrationVO.class).toString()).doesNotContain("k".repeat(43));
            post(client, port, "registerGuest", "wrong-token", json.writeValueAsString(call));
            assertThat(output.getAll())
                    .doesNotContain(
                            "private-contact-123",
                            "private-name",
                            "private-key-123",
                            "k".repeat(43));
        }
    }

    @Test
    void originalChallengeReplayPreservesExpiredCredential() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + port), TOKEN);
            var remote =
                    new RemoteReceptionService(
                            http.client(
                                    "BEAUTY",
                                    BeautyReceptionInternalApi.class,
                                    Duration.ofSeconds(2)),
                            new InternalBindings(TOKEN));
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                    () -> {
                        var first = remote.issueArrivalChallenge("E", "S", "actor", "original-key");
                        var replay =
                                remote.issueArrivalChallenge("E", "S", "actor", "original-key");
                        assertThat(replay).isEqualTo(first);
                        assertThat(replay.expiresAt())
                                .isEqualTo(TIME.plusSeconds(90))
                                .isBefore(OffsetDateTime.now());
                        return null;
                    });
            assertThat(context.getBean(AtomicInteger.class)).hasValue(2);
        }
    }

    @Test
    void operationWireDispatchesAppointmentReceptionAndSchema2WithoutMutationOnGet() {
        try (var context =
                new SpringApplicationBuilder(Host.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--shop.beauty.enabled=true",
                                "--shop.services.internal-token=" + TOKEN)) {
            var owner = context.getBean(ServiceOperationQueryService.class);
            var recovery = context.getBean(ServiceOperationRecoveryService.class);
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + port), TOKEN);
            var remote =
                    new RemoteServiceOperationQueryService(
                            http.client(
                                    "BEAUTY",
                                    BeautyReceptionInternalApi.class,
                                    Duration.ofSeconds(2)),
                            new InternalBindings(TOKEN));
            for (String action : List.of("CREATE", "CHECK_IN", "SUBMIT_SOURCE", "ADD_SOURCE")) {
                doReturn(
                                new OperationVO(
                                        "O",
                                        action.equals("CREATE") ? "A" : null,
                                        "PENDING",
                                        action,
                                        "RETRY",
                                        action.endsWith("SOURCE") ? "W" : null,
                                        "DEPENDENCY_UNAVAILABLE"))
                        .when(owner)
                        .getOperation("E", "S", "actor", "O");
                DelegatedIdentityContext.call(
                        new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                        () -> {
                            var result = remote.getOperation("E", "S", "actor", "O");
                            assertThat(result.action()).isEqualTo(action);
                            assertThat(result.failureCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                            return null;
                        });
            }
            verifyNoInteractions(recovery);
        }
    }

    @RestController
    static class FaultEndpoint {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> mode = new AtomicReference<>("error200");
        private final InternalBindings bindings = new InternalBindings(TOKEN);

        @PostMapping({
            "/internal/fault/internal/beauty/reception/getWorkOrder",
            "/internal/fault/internal/beauty/reception/getMyWorkOrder"
        })
        ResponseEntity<String> detailFault(
                @RequestBody InternalCall<BeautyReceptionInternalApi.WorkOrderRef> call) {
            requests.incrementAndGet();
            if (mode.get().equals("timeout")) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return response(503, "");
            }
            var json = JsonMapper.builder().build();
            Object result =
                    result(
                            call.binding().action().equals("GET_WORK_ORDER")
                                    ? WorkOrderDetailVO.class
                                    : MyWorkOrderVO.class);
            var body =
                    (ObjectNode)
                            json.readTree(json.writeValueAsString(bindings.reply(call, result)));
            var detail = (ObjectNode) body.get("result");
            var tasks = (ArrayNode) detail.get("tasks");
            ((ArrayNode) detail.get("workOrder").get("taskNos")).add("T2");
            var second = ((ObjectNode) tasks.get(0)).deepCopy();
            second.put("taskNo", "T2");
            tasks.add(second);
            switch (mode.get()) {
                case "emptyTasks" -> tasks.removeAll();
                case "missingTask" -> tasks.remove(1);
                case "missingTasksField" -> detail.remove("tasks");
                case "foreignTask" -> ((ObjectNode) tasks.get(1)).put("taskNo", "FOREIGN");
                case "duplicateTask" -> ((ObjectNode) tasks.get(1)).put("taskNo", "T");
                case "extraTask" -> {
                    var extra = second.deepCopy();
                    extra.put("taskNo", "EXTRA");
                    tasks.add(extra);
                }
                case "reorderedTasks" -> {
                    var first = tasks.remove(0);
                    tasks.add(first);
                }
                default -> {}
            }
            return response(200, body.toString());
        }

        @PostMapping("/internal/fault/internal/beauty/reception/registerGuest")
        ResponseEntity<String> fault(
                @RequestBody InternalCall<BeautyReceptionInternalApi.RegisterGuest> call) {
            requests.incrementAndGet();
            String current = mode.get();
            if (current.equals("timeout")) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return response(503, "");
            }
            String digest = bindings.digest(call);
            if (current.equals("error200")) {
                return response(200, "{\"code\":10403,\"data\":null}");
            }
            if (List.of("null", "empty", "malformed").contains(current)) {
                return response(
                        200,
                        current.equals("empty") ? "" : current.equals("null") ? "null" : "{broken");
            }
            if (current.equals("unbound404")) {
                return response(404, "");
            }
            if (List.of(
                            "bound404",
                            "bound403",
                            "bound409",
                            "statusMismatch",
                            "digestMismatch",
                            "unknownCode",
                            "dependency")
                    .contains(current)) {
                int status =
                        current.equals("bound403")
                                ? 403
                                : current.equals("bound409")
                                        ? 409
                                        : current.equals("dependency") ? 503 : 404;
                String code =
                        current.equals("bound403")
                                ? "FORBIDDEN"
                                : current.equals("bound409")
                                        ? "CONFLICT"
                                        : current.equals("statusMismatch")
                                                ? "FORBIDDEN"
                                                : current.equals("unknownCode")
                                                        ? "INVENTED"
                                                        : current.equals("dependency")
                                                                ? "BEAUTY_DEPENDENCY_UNAVAILABLE"
                                                                : "NOT_FOUND";
                return ResponseEntity.status(status)
                        .header("X-Internal-Error-Code", code)
                        .header(
                                "X-Internal-Request-Digest",
                                current.equals("digestMismatch") ? "0".repeat(64) : digest)
                        .build();
            }
            var json = JsonMapper.builder().build();
            var body =
                    (ObjectNode)
                            json.readTree(
                                    json.writeValueAsString(
                                            bindings.reply(
                                                    call,
                                                    (GuestRegistrationVO)
                                                            result(GuestRegistrationVO.class))));
            if (current.equals("missingNullable")) {
                ((ObjectNode) body.get("result")).remove("operationNo");
            }
            if (current.equals("missingRevision")) {
                ((ObjectNode) body.get("result")).remove("revision");
            }
            if (current.equals("wrongDigest")) {
                body.put("requestDigest", "0".repeat(64));
            }
            var binding = (ObjectNode) body.get("binding");
            var identity = (ObjectNode) binding.get("identity");
            switch (current) {
                case "wrongActor" -> identity.put("actorNo", "other");
                case "wrongTenant" -> identity.put("tenantNo", "OTHER");
                case "wrongRealm" -> identity.put("realm", "CONSUMER");
                case "wrongKind" -> identity.put("subjectKind", "MCH");
                case "wrongEndpoint" -> binding.put("endpoint", "BAPI-032");
                case "wrongAction" -> binding.put("action", "CLAIM_GUEST");
                case "wrongEntity" -> binding.put("entityNo", "other");
                case "wrongStore" -> binding.put("storeNo", "other");
                case "wrongTarget" -> binding.put("targetNo", "other");
                default -> {}
            }
            return response(200, body.toString());
        }

        private static ResponseEntity<String> response(int status, String body) {
            return ResponseEntity.status(status)
                    .header("Content-Type", "application/json")
                    .body(body);
        }
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

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class
    })
    @Import({
        InternalBeautyGuestController.class,
        InternalBeautyOperationController.class,
        InternalBeautyReceptionController.class,
        InternalBeautySubmissionController.class,
        InternalBeautyTraceController.class,
        InternalBeautyWorkOrderController.class,
        GlobalExceptionHandler.class,
        ApiResponseWrapper.class,
        FaultEndpoint.class
    })
    static class Host {
        @Bean
        AtomicInteger calls() {
            return new AtomicInteger();
        }

        private <T> T owner(Class<T> type, AtomicInteger calls) {
            Answer<Object> answer =
                    invocation -> {
                        calls.incrementAndGet();
                        assertThat(SecurityUtils.requireUser().userNo()).isEqualTo("actor");
                        if (invocation.getMethod().getName().equals("openWorkOrder")) {
                            return new WorkOrderVO(
                                    "W",
                                    null,
                                    1,
                                    "OPEN",
                                    null,
                                    "UNBOUND",
                                    "O",
                                    "SUCCEEDED",
                                    List.of("T"),
                                    null);
                        }
                        return result(invocation.getMethod().getReturnType());
                    };
            return mock(type, answer);
        }

        @Bean
        AppointmentService appointmentService(AtomicInteger calls) {
            return owner(AppointmentService.class, calls);
        }

        @Bean
        GuestRegistrationService guestRegistrationService(AtomicInteger calls) {
            return owner(GuestRegistrationService.class, calls);
        }

        @Bean
        ReceptionService receptionService(AtomicInteger calls) {
            return owner(ReceptionService.class, calls);
        }

        @Bean
        ServiceOperationQueryService serviceOperationQueryService(AtomicInteger calls) {
            return owner(ServiceOperationQueryService.class, calls);
        }

        @Bean
        ServiceOperationRecoveryService serviceOperationRecoveryService(AtomicInteger calls) {
            return owner(ServiceOperationRecoveryService.class, calls);
        }

        @Bean
        ServiceSubmissionService serviceSubmissionService(AtomicInteger calls) {
            return owner(ServiceSubmissionService.class, calls);
        }

        @Bean
        WorkOrderBillService workOrderBillService(AtomicInteger calls) {
            return owner(WorkOrderBillService.class, calls);
        }

        @Bean
        WorkOrderService workOrderService(AtomicInteger calls) {
            return owner(WorkOrderService.class, calls);
        }
    }
}
