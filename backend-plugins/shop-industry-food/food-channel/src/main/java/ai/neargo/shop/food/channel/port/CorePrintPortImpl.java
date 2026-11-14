package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.printing.PrintInternalApi;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Standalone FOOD calls the shared PLATFORM owner and rejects empty or cross-store transport
 * receipts.
 */
public class CorePrintPortImpl implements PrintPort {
    private final Supplier<PrintInternalApi> api;

    public CorePrintPortImpl(Supplier<PrintInternalApi> api) {
        this.api = api;
    }

    @Override
    public Configuration configuration(Scope scope) {
        var result = invoke(() -> api.get().configuration(scope));
        if (result == null
                || result.devices() == null
                || result.templates() == null
                || result.routes() == null) {
            invalid();
        }
        return result;
    }

    @Override
    public DeviceVO bindDevice(Scope scope, DeviceReq req) {
        var result =
                invoke(() -> api.get().bindDevice(new PrintInternalApi.DeviceCommand(scope, req)));
        if (result == null
                || !req.deviceNo().equals(result.deviceNo())
                || !req.bridgeActorNo().equals(result.bridgeActorNo())) {
            invalid();
        }
        return result;
    }

    @Override
    public DeviceVO revokeDevice(Scope scope, String no, String reason, String key) {
        var result =
                invoke(
                        () ->
                                api.get()
                                        .revokeDevice(
                                                new PrintInternalApi.RevokeDeviceCommand(
                                                        scope, no, reason, key)));
        if (result == null
                || !no.equals(result.deviceNo())
                || !"DISABLED".equals(result.status())
                || result.authorizationVersion() < 1) {
            invalid();
        }
        return result;
    }

    @Override
    public TemplateReq publishTemplate(Scope scope, TemplateReq req) {
        var result =
                invoke(
                        () ->
                                api.get()
                                        .publishTemplate(
                                                new PrintInternalApi.TemplateCommand(scope, req)));
        if (!req.equals(result)) {
            invalid();
        }
        return result;
    }

    @Override
    public RouteReq putRoute(Scope scope, RouteReq req) {
        var result =
                invoke(() -> api.get().putRoute(new PrintInternalApi.RouteCommand(scope, req)));
        if (!req.equals(result)) {
            invalid();
        }
        return result;
    }

    @Override
    public List<JobVO> enqueue(Scope scope, Enqueue req) {
        var result =
                invoke(() -> api.get().enqueue(new PrintInternalApi.EnqueueCommand(scope, req)));
        if (result == null || result.isEmpty()) {
            invalid();
        }
        for (var row : result) {
            valid(scope, row, null);
            if (!req.sourceEventNo().equals(row.sourceEventNo())
                    || !req.document().equals(row.document())) {
                invalid();
            }
        }
        return result;
    }

    @Override
    public List<JobVO> list(Scope scope, String after, int limit) {
        var result =
                invoke(() -> api.get().list(new PrintInternalApi.ListQuery(scope, after, limit)));
        if (result == null || result.size() > limit) {
            invalid();
        }
        result.forEach(row -> valid(scope, row, null));
        return result;
    }

    @Override
    public JobVO get(Scope scope, String no) {
        return valid(
                scope, invoke(() -> api.get().get(new PrintInternalApi.JobQuery(scope, no))), no);
    }

    @Override
    public JobVO retry(Scope scope, String no) {
        var result =
                valid(
                        scope,
                        invoke(() -> api.get().retry(new PrintInternalApi.JobQuery(scope, no))),
                        no);
        if (!"PENDING".equals(result.status())) {
            invalid();
        }
        return result;
    }

    @Override
    public JobVO reprint(Scope scope, String no, String reason, String key) {
        var result =
                valid(
                        scope,
                        invoke(
                                () ->
                                        api.get()
                                                .reprint(
                                                        new PrintInternalApi.ReprintCommand(
                                                                scope, no, reason, key))),
                        null);
        if (!no.equals(result.originalJobNo())
                || !reason.equals(result.reprintReason())
                || !scope.actorNo().equals(result.requestedBy())) {
            invalid();
        }
        return result;
    }

    @Override
    public DeviceVO heartbeat(Scope scope, String device, boolean online) {
        var result =
                invoke(
                        () ->
                                api.get()
                                        .heartbeat(
                                                new PrintInternalApi.HeartbeatCommand(
                                                        scope, device, online)));
        if (result == null
                || !device.equals(result.deviceNo())
                || result.online() != online
                || !scope.actorNo().equals(result.bridgeActorNo())) {
            invalid();
        }
        return result;
    }

    @Override
    public Claim claim(Scope scope, String device) {
        var envelope =
                invoke(() -> api.get().claim(new PrintInternalApi.DeviceQuery(scope, device)));
        if (envelope == null) {
            invalid();
        }
        var result = envelope.claim();
        // The typed HTTP codec validates the owner's LocalDateTime representation. A past lease
        // remains a real claim receipt; only the owner decides whether its response can be
        // acknowledged.
        if (result != null
                && (!device.equals(result.deviceNo())
                        || !key(result.jobNo())
                        || !key(result.attemptNo())
                        || !uuid(result.leaseToken())
                        || result.leaseUntil() == null
                        || result.renderedContent() == null
                        || result.renderedContent().isBlank())) {
            invalid();
        }
        return result;
    }

    @Override
    public JobVO acknowledge(Scope scope, String device, Acknowledge req) {
        var result =
                valid(
                        scope,
                        invoke(
                                () ->
                                        api.get()
                                                .acknowledge(
                                                        new PrintInternalApi.AckCommand(
                                                                scope, device, req))),
                        null);
        if (!device.equals(result.deviceNo())
                || !key(result.jobNo())
                || result.attempts().isEmpty()) {
            invalid();
        }
        // The owner returns every attempt in ascending sequence order; history membership alone
        // must never make an obsolete hardware acknowledgement look accepted for the current lease.
        int sequence = 0;
        var numbers = new HashSet<String>();
        for (var attempt : result.attempts()) {
            if (attempt == null
                    || !key(attempt.attemptNo())
                    || attempt.sequenceNo() != ++sequence
                    || !numbers.add(attempt.attemptNo())) {
                invalid();
            }
        }
        var latest = result.attempts().getLast();
        String normalized =
                "FAILED".equals(req.status()) && !"NOT_PRINTED".equals(req.responseCode())
                        ? "UNKNOWN"
                        : req.status();
        if (!Set.of("SUCCESS", "FAILED", "UNKNOWN").contains(normalized == null ? "" : normalized)
                || !req.attemptNo().equals(latest.attemptNo())
                || !normalized.equals(latest.status())
                || !Objects.equals(req.responseCode(), latest.responseCode())) {
            invalid();
        }
        // retry leaves the old currentAttemptNo in place until the next claim. An exact duplicate
        // NOT_PRINTED receipt legitimately returns PENDING with that same latest FAILED attempt.
        if (!normalized.equals(result.status())
                && !("FAILED".equals(normalized) && "PENDING".equals(result.status()))) {
            invalid();
        }
        return result;
    }

    private JobVO valid(Scope scope, JobVO result, String no) {
        if (result == null
                || !scope.storeNo().equals(result.storeNo())
                || result.jobNo() == null
                || no != null && !no.equals(result.jobNo())
                || result.document() == null
                || result.renderedContent() == null
                || result.attempts() == null
                || !Set.of("PENDING", "SENT", "SUCCESS", "FAILED", "UNKNOWN")
                        .contains(result.status() == null ? "" : result.status())) {
            invalid();
        }
        return result;
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, call);
        } catch (ServiceCallException failure) {
            ErrorCode business =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (business != null) {
                throw BizException.of(business);
            }
            throw failure;
        }
    }

    private static boolean key(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,32}");
    }

    private static boolean uuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void invalid() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
