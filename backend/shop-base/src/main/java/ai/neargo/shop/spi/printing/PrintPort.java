package ai.neargo.shop.spi.printing;

import java.time.LocalDateTime;
import java.util.List;

/** Shared durable printing boundary; delivery receipts never change financial state. */
public interface PrintPort {
    record Scope(String entityNo, String storeNo, String actorNo) {}

    record DeviceReq(
            String deviceNo,
            String vendor,
            String connectionType,
            String credentialRef,
            String bridgeActorNo,
            String capabilities,
            boolean active) {}

    record DeviceVO(
            String deviceNo,
            String vendor,
            String connectionType,
            String bridgeActorNo,
            String status,
            String capabilities,
            LocalDateTime lastSeenAt,
            boolean online,
            long authorizationVersion) {
        public DeviceVO(
                String deviceNo,
                String vendor,
                String connectionType,
                String bridgeActorNo,
                String status,
                String capabilities,
                LocalDateTime lastSeenAt,
                boolean online) {
            this(
                    deviceNo,
                    vendor,
                    connectionType,
                    bridgeActorNo,
                    status,
                    capabilities,
                    lastSeenAt,
                    online,
                    0);
        }
    }

    record TemplateReq(
            String templateNo, String templateCode, int revision, String scene, String content) {}

    record RouteReq(
            String routeNo,
            String scene,
            String stationNo,
            String deviceNo,
            String templateNo,
            int copies) {}

    record Configuration(
            List<DeviceVO> devices, List<TemplateReq> templates, List<RouteReq> routes) {
        public Configuration {
            devices = List.copyOf(devices);
            templates = List.copyOf(templates);
            routes = List.copyOf(routes);
        }
    }

    /**
     * Only explicitly selected source facts belong here: no buyer phone, address, secrets or raw
     * entity JSON.
     */
    record Line(String label, String quantity, Long amountMinor, String note) {}

    record Document(
            int schemaVersion,
            String scene,
            String sourceRef,
            String title,
            String currency,
            List<Line> lines,
            String notice) {
        public Document {
            lines = List.copyOf(lines);
        }
    }

    record Enqueue(String sourceEventNo, String stationNo, String partNo, Document document) {}

    record AttemptVO(
            String attemptNo,
            int sequenceNo,
            String status,
            String responseCode,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {}

    record JobVO(
            String jobNo,
            String storeNo,
            String sourceEventNo,
            String scene,
            String deviceNo,
            String partNo,
            int copyIndex,
            String templateNo,
            String status,
            String originalJobNo,
            String reprintReason,
            String requestedBy,
            Document document,
            String renderedContent,
            List<AttemptVO> attempts) {
        public JobVO {
            attempts = List.copyOf(attempts);
        }
    }

    /** The token fences every hardware response, including responses arriving after a timeout. */
    record Claim(
            String jobNo,
            String deviceNo,
            String attemptNo,
            String leaseToken,
            LocalDateTime leaseUntil,
            String renderedContent) {}

    record Acknowledge(
            String attemptNo,
            String leaseToken,
            String status,
            String channelJobNo,
            String responseCode) {}

    Configuration configuration(Scope scope);

    DeviceVO bindDevice(Scope scope, DeviceReq request);

    DeviceVO revokeDevice(Scope scope, String deviceNo, String reason, String requestKey);

    TemplateReq publishTemplate(Scope scope, TemplateReq request);

    RouteReq putRoute(Scope scope, RouteReq request);

    List<JobVO> enqueue(Scope scope, Enqueue request);

    List<JobVO> list(Scope scope, String afterJobNo, int limit);

    JobVO get(Scope scope, String jobNo);

    JobVO retry(Scope scope, String jobNo);

    JobVO reprint(Scope scope, String jobNo, String reason, String requestKey);

    DeviceVO heartbeat(Scope scope, String deviceNo, boolean online);

    Claim claim(Scope scope, String deviceNo);

    JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request);
}
