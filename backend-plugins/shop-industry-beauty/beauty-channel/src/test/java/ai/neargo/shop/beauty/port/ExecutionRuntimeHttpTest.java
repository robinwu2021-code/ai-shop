package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeInternalApi;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineReply;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.svc.ExecutionRuntimeBindings;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ExecutionRuntimeHttpTest {
    @BeforeEach
    void clearInheritedIdentity() {
        SecurityContextHolder.clearContext();
    }

    private ExecutionReference reference() {
        return reference("S");
    }

    private ExecutionReference reference(String store) {
        String h = "a".repeat(64);
        return new ExecutionReference(
                        "SERVICE_EXECUTION_REFERENCE_V1",
                        new ExecutionScope("MAIN", "E", store),
                        ExecutionReference.SourceKind.STORE_ORDER,
                        "MANUAL_EXECUTION_V1",
                        "execution",
                        "grant",
                        "unit",
                        "task",
                        "work",
                        1,
                        1,
                        h,
                        h,
                        h,
                        h,
                        h,
                        "fence",
                        1,
                        h,
                        "BTRC",
                        h,
                        "action",
                        h,
                        h,
                        null)
                .signed();
    }

    @Test
    void realMachineOriginalRetainsExactPermanentIdentityWithoutHumanAndRejectsReplyRebinding()
            throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new ExecutionRuntimeBindings("runtime-key");
        var seen = new AtomicReference<MachineCall<ResourceOccupationContract.OriginalLookup>>();
        var foreign = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/resource-original",
                exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("runtime-key");
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("actorNo", "canonicalAccountNo");
                    var call =
                            json.readValue(
                                    bytes,
                                    new TypeReference<
                                            MachineCall<
                                                    ResourceOccupationContract
                                                            .OriginalLookup>>() {});
                    seen.set(call);
                    var result =
                            new ResourceOccupationContract.OriginalResult(
                                            "RESOURCE_OCCUPATION_ORIGINAL_V1",
                                            call.request(),
                                            ResourceOccupationContract.Outcome.UNKNOWN,
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            false,
                                            0,
                                            "ORIGINAL_UNKNOWN",
                                            null)
                                    .signed();
                    var reply = bindings.reply(call, result);
                    var data =
                            json.writeValueAsBytes(
                                    foreign.get()
                                            ? new MachineReply<>(
                                                    Purpose.START_ORIGINAL,
                                                    reply.referenceHash(),
                                                    reply.requestDigest(),
                                                    result)
                                            : reply);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "runtime-key");
            var port =
                    new RemoteExecutionRuntimePort(
                            http.client(
                                    "PLATFORM",
                                    ExecutionRuntimeInternalApi.class,
                                    Duration.ofSeconds(2)),
                            "runtime-key");
            var ref = reference();
            var source =
                    new ServiceTaskStartAuthorityPort.StartAuthorityLookup(
                            ref, "submission", "b".repeat(64));
            var lookup =
                    new ResourceOccupationContract.OriginalLookup(
                            ref,
                            source,
                            "key",
                            ResourceOccupationContract.stableRequestHash(ref, source, "key"),
                            null);
            var prior = SecurityContextHolder.getContext();
            assertThat(port.queryOriginal(lookup).outcome())
                    .isEqualTo(ResourceOccupationContract.Outcome.UNKNOWN);
            assertThat(seen.get().reference()).isEqualTo(ref);
            assertThat(seen.get().request()).isEqualTo(lookup);
            assertThat(seen.get().purpose()).isEqualTo(Purpose.RESOURCE_ORIGINAL);
            assertThat(SecurityContextHolder.getContext()).isSameAs(prior);
            foreign.set(true);
            assertThatThrownBy(() -> port.queryOriginal(lookup))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void currentProofAndCancellationUseHumanWhileOriginalCancellationIsMachineAndEmptyIsNotASeal()
            throws Exception {
        var json = JsonMapper.builder().build();
        var human = new InternalBindings("runtime-key");
        var machine = new ExecutionRuntimeBindings("runtime-key");
        var ref = reference();
        var source =
                new ServiceTaskStartAuthorityPort.StartAuthorityLookup(
                        ref, "submission", "b".repeat(64));
        var original =
                new ResourceOccupationContract.OriginalLookup(
                        ref,
                        source,
                        "key",
                        ResourceOccupationContract.stableRequestHash(ref, source, "key"),
                        null);
        var authority =
                new ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup(
                        ref, "workflow", "c".repeat(64));
        var request =
                new ResourceOccupationLifecycleContract.Request(
                                authority,
                                original,
                                "occupation",
                                1,
                                ResourceOccupationLifecycleContract.Action.PAUSE,
                                "workflow",
                                "d".repeat(64),
                                "",
                                List.of(),
                                List.of())
                        .signed();
        var cancelled =
                new ResourceOccupationLifecycleContract.Cancellation(
                                request,
                                ResourceOccupationLifecycleContract.CancellationOutcome
                                        .NO_EFFECT_SEALED,
                                null,
                                "workflow",
                                "seal",
                                null)
                        .signed();
        var currentSeen =
                new AtomicReference<
                        InternalCall<ExecutionRuntimeInternalApi.StartCurrentRequest>>();
        var cancelSeen =
                new AtomicReference<InternalCall<ResourceOccupationLifecycleContract.Request>>();
        var originalSeen =
                new AtomicReference<MachineCall<ResourceOccupationLifecycleContract.Request>>();
        var pending = new AtomicBoolean();
        var wrongUse = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/validate-current-start",
                exchange -> {
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<
                                            InternalCall<
                                                    ExecutionRuntimeInternalApi
                                                            .StartCurrentRequest>>() {});
                    currentSeen.set(call);
                    var now = Instant.parse("2026-11-10T00:00:00Z");
                    var segment =
                            new ResourceOccupationLifecycleContract.Segment(
                                    "segment",
                                    "demand",
                                    "resource",
                                    "person",
                                    1,
                                    now,
                                    null,
                                    false,
                                    null);
                    var segments = List.of(segment);
                    var proof =
                            new ResourceOccupationLifecycleContract.CurrentWorkflowProof(
                                            source,
                                            original,
                                            wrongUse.get() ? authority : null,
                                            new ResourceOccupationContract.ActualOriginal(
                                                    "owner",
                                                    "RBC",
                                                    "RESOURCE_OCCUPATION_V1",
                                                    "a".repeat(64)),
                                            "occupation",
                                            1,
                                            ExecutionEvidenceContract.hash(
                                                    "RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1",
                                                    segments),
                                            segments,
                                            List.of(),
                                            List.of(),
                                            now,
                                            now.plusSeconds(30),
                                            "validation",
                                            "validation-result",
                                            null)
                                    .signed();
                    var data = json.writeValueAsBytes(human.reply(call, proof));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/validate-current-workflow",
                exchange -> {
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<
                                            InternalCall<
                                                    ResourceOccupationLifecycleContract
                                                            .Request>>() {});
                    assertThat(call.binding().action()).isEqualTo("VALIDATE_CURRENT_WORKFLOW");
                    assertThat(call.binding().identity().actorNo()).isEqualTo("ACTOR");
                    var now = Instant.parse("2026-11-10T00:00:00Z");
                    var segments =
                            List.of(
                                    new ResourceOccupationLifecycleContract.Segment(
                                            "segment",
                                            "demand",
                                            "resource",
                                            "person",
                                            1,
                                            now,
                                            null,
                                            false,
                                            null));
                    var proof =
                            new ResourceOccupationLifecycleContract.CurrentWorkflowProof(
                                            source,
                                            original,
                                            authority,
                                            new ResourceOccupationContract.ActualOriginal(
                                                    "owner",
                                                    "RBC",
                                                    "RESOURCE_OCCUPATION_V1",
                                                    "a".repeat(64)),
                                            "occupation",
                                            2,
                                            ExecutionEvidenceContract.hash(
                                                    "RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1",
                                                    segments),
                                            segments,
                                            List.of(),
                                            List.of(),
                                            now,
                                            now.plusSeconds(30),
                                            "validation-workflow",
                                            "validation-result",
                                            null)
                                    .signed();
                    var data = json.writeValueAsBytes(human.reply(call, proof));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/lifecycle-cancel",
                exchange -> {
                    var call =
                            json.readValue(
                                    exchange.getRequestBody().readAllBytes(),
                                    new TypeReference<
                                            InternalCall<
                                                    ResourceOccupationLifecycleContract
                                                            .Request>>() {});
                    cancelSeen.set(call);
                    var data = json.writeValueAsBytes(human.reply(call, cancelled));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/lifecycle-cancellation-original",
                exchange -> {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("actorNo");
                    var call =
                            json.readValue(
                                    bytes,
                                    new TypeReference<
                                            MachineCall<
                                                    ResourceOccupationLifecycleContract
                                                            .Request>>() {});
                    originalSeen.set(call);
                    var data =
                            json.writeValueAsBytes(
                                    machine.reply(
                                            call,
                                            new ExecutionRuntimeInternalApi
                                                    .LifecycleCancellationObservation(
                                                    pending.get() ? null : cancelled)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "runtime-key");
            var port =
                    new RemoteExecutionRuntimePort(
                            http.client(
                                    "PLATFORM",
                                    ExecutionRuntimeInternalApi.class,
                                    Duration.ofSeconds(2)),
                            "runtime-key");
            assertThatThrownBy(() -> port.validateCurrentStart(source, original))
                    .isInstanceOf(RuntimeException.class);
            assertThat(currentSeen.get()).isNull();
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                    () -> {
                        assertThat(
                                        port.validateCurrentStart(source, original)
                                                .validationCommandNo())
                                .isEqualTo("validation");
                        assertThat(currentSeen.get().binding().identity().actorNo())
                                .isEqualTo("ACTOR");
                        assertThat(currentSeen.get().binding().action())
                                .isEqualTo("VALIDATE_CURRENT_START");
                        assertThat(currentSeen.get().request().original()).isEqualTo(original);
                        wrongUse.set(true);
                        assertThatThrownBy(() -> port.validateCurrentStart(source, original))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(port.validateCurrentWorkflow(request).workflow())
                                .isEqualTo(authority);
                        assertThat(port.cancelBeforeEffect(request)).isEqualTo(cancelled);
                        assertThat(cancelSeen.get().binding().action())
                                .isEqualTo("LIFECYCLE_CANCEL");
                        assertThat(cancelSeen.get().binding().identity().actorNo())
                                .isEqualTo("ACTOR");
                        return null;
                    });
            assertThat(port.queryCancellationOriginal(request)).contains(cancelled);
            assertThat(originalSeen.get().purpose())
                    .isEqualTo(Purpose.LIFECYCLE_CANCELLATION_ORIGINAL);
            assertThat(originalSeen.get().request()).isEqualTo(request);
            pending.set(true);
            assertThat(port.queryCancellationOriginal(request)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void machineCurrentReadReturnsRevocationRevisionAndStopFactsWithoutRebindingScopeOrActualOwner()
            throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new ExecutionRuntimeBindings("runtime-key");
        var mode = new AtomicInteger();
        var seen = new AtomicReference<MachineCall<ResourceOccupationContract.OriginalLookup>>();
        var ref = reference();
        var source =
                new ServiceTaskStartAuthorityPort.StartAuthorityLookup(
                        ref, "submission", "b".repeat(64));
        var actual =
                new ResourceOccupationContract.ActualOriginal(
                        "owner", "RBC", "RESOURCE_OCCUPATION_V1", "a".repeat(64));
        var original =
                new ResourceOccupationContract.OriginalLookup(
                        ref,
                        source,
                        "key",
                        ResourceOccupationContract.stableRequestHash(ref, source, "key"),
                        actual);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/resource-current",
                exchange -> {
                    byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                    assertThat(new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("actorNo", "identity");
                    var call =
                            json.readValue(
                                    requestBytes,
                                    new TypeReference<
                                            MachineCall<
                                                    ResourceOccupationContract
                                                            .OriginalLookup>>() {});
                    seen.set(call);
                    var resultOriginal = original;
                    if (mode.get() == 1) {
                        var foreign = reference("FOREIGN");
                        var foreignSource =
                                new ServiceTaskStartAuthorityPort.StartAuthorityLookup(
                                        foreign, "submission", "b".repeat(64));
                        resultOriginal =
                                new ResourceOccupationContract.OriginalLookup(
                                        foreign,
                                        foreignSource,
                                        "key",
                                        ResourceOccupationContract.stableRequestHash(
                                                foreign, foreignSource, "key"),
                                        actual);
                    }
                    var resultActual =
                            mode.get() == 2
                                    ? new ResourceOccupationContract.ActualOriginal(
                                            "FOREIGN",
                                            "RBC",
                                            "RESOURCE_OCCUPATION_V1",
                                            "a".repeat(64))
                                    : actual;
                    var now = Instant.parse("2026-11-10T00:00:00Z");
                    var segments =
                            List.of(
                                    new ResourceOccupationLifecycleContract.Segment(
                                            "segment",
                                            "demand",
                                            "resource",
                                            "person",
                                            1,
                                            now,
                                            null,
                                            false,
                                            null));
                    var current =
                            new ResourceOccupationLifecycleContract.CurrentOccupation(
                                            resultOriginal,
                                            resultActual,
                                            "occupation",
                                            4,
                                            "PAUSED",
                                            null,
                                            segments,
                                            true,
                                            true,
                                            3,
                                            now,
                                            null)
                                    .signed();
                    var bytes = json.writeValueAsBytes(bindings.reply(call, current));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "runtime-key");
            var port =
                    new RemoteExecutionRuntimePort(
                            http.client(
                                    "PLATFORM",
                                    ExecutionRuntimeInternalApi.class,
                                    Duration.ofSeconds(2)),
                            "runtime-key");
            var previous = SecurityContextHolder.getContext();
            var result = port.queryCurrent(original);
            assertThat(result.revision()).isEqualTo(4);
            assertThat(result.state()).isEqualTo("PAUSED");
            assertThat(result.stopRequired()).isTrue();
            assertThat(result.conflictRequired()).isTrue();
            assertThat(result.segments().getFirst().endedAt()).isNull();
            assertThat(seen.get().purpose()).isEqualTo(Purpose.RESOURCE_CURRENT);
            assertThat(seen.get().request()).isEqualTo(original);
            assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
            mode.set(1);
            assertThatThrownBy(() -> port.queryCurrent(original))
                    .isInstanceOf(RuntimeException.class);
            mode.set(2);
            assertThatThrownBy(() -> port.queryCurrent(original))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bookingAllocationReadUsesRealGenerationAndBindsWholeConfirmedOriginalWithoutActor()
            throws Exception {
        var json = JsonMapper.builder().build();
        var bindings = new ExecutionRuntimeBindings("runtime-key");
        var foreign = new AtomicBoolean();
        var seen = new AtomicReference<MachineCall<ResourceBookingExecutionEvidencePort.Lookup>>();
        var at = Instant.parse("2026-11-10T00:00:00Z");
        var plan =
                ResourcePlanContract.encode(
                        List.of(
                                new ResourcePlanContract.Demand(
                                        "R",
                                        "ITEM:REQ:0",
                                        1,
                                        List.of(
                                                new ResourcePlanContract.Interval(
                                                        at, at.plusSeconds(300))))),
                        List.of(
                                new ResourcePlanContract.Qualification(
                                        "R", "ITEM:REQ:0", null, null, false)));
        var hash = ResourcePlanContract.hash(plan);
        var binding =
                new ResourceSourceAuthorityPort.Binding(
                        "MAIN",
                        "E",
                        "S",
                        "CONFIRM",
                        "BEAUTY_APPOINTMENT",
                        "APPOINTMENT",
                        42,
                        "CONFIRM",
                        "a".repeat(64),
                        hash);
        var lookup =
                new ResourceBookingExecutionEvidencePort.Lookup(
                        reference(), binding, "HOLD", 6, "ITEM", 42, "b".repeat(64), plan, hash);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ExecutionRuntimeInternalApi.PATH + "/booking-execution-original",
                exchange -> {
                    var bytes = exchange.getRequestBody().readAllBytes();
                    assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("actorNo");
                    var call =
                            json.readValue(
                                    bytes,
                                    new TypeReference<
                                            MachineCall<
                                                    ResourceBookingExecutionEvidencePort
                                                            .Lookup>>() {});
                    seen.set(call);
                    var l = call.request();
                    if (foreign.get())
                        l =
                                new ResourceBookingExecutionEvidencePort.Lookup(
                                        l.reference(),
                                        l.binding(),
                                        "OTHER_HOLD",
                                        l.leaseRevision(),
                                        l.appointmentItemNo(),
                                        l.arrangementRevision(),
                                        l.arrangementHash(),
                                        l.wholePlanJson(),
                                        l.wholePlanHash());
                    var result =
                            new ResourceBookingExecutionEvidencePort.Evidence(
                                            l,
                                            "ACTUAL_OWNER",
                                            8,
                                            List.of(
                                                    new ResourceBookingExecutionEvidencePort
                                                            .Allocation(
                                                            "ACTUAL_UNIT",
                                                            "BUCKET",
                                                            "R",
                                                            "ITEM:REQ:0",
                                                            at,
                                                            at.plusSeconds(300),
                                                            1,
                                                            8,
                                                            false)),
                                            at,
                                            null)
                                    .signed();
                    var data = json.writeValueAsBytes(bindings.reply(call, result));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var http =
                    new InternalHttp(
                            name ->
                                    Optional.of(
                                            "http://127.0.0.1:" + server.getAddress().getPort()),
                            "runtime-key");
            var port =
                    new RemoteExecutionRuntimePort(
                            http.client(
                                    "PLATFORM",
                                    ExecutionRuntimeInternalApi.class,
                                    Duration.ofSeconds(2)),
                            "runtime-key");
            assertThat(port.queryOriginal(lookup).allocationRevision()).isEqualTo(8);
            assertThat(seen.get().purpose()).isEqualTo(Purpose.BOOKING_EXECUTION_ORIGINAL);
            assertThat(seen.get().request()).isEqualTo(lookup);
            foreign.set(true);
            assertThatThrownBy(() -> port.queryOriginal(lookup))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            server.stop(0);
        }
    }
}
