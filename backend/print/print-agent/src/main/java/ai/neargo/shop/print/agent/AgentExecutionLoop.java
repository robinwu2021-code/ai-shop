package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class AgentExecutionLoop implements AutoCloseable {
    private final AgentHttpClient client;
    private final LocalDispatchStore store;
    private final DesktopPrintExecutor executor;
    private final long session;
    private final List<String> printerNos;
    private final List<DiscoveredPrinter> discovery;
    private final AgentJson json = new AgentJson();
    private final ExecutorService spool = Executors.newSingleThreadExecutor();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AgentExecutionLoop(
            AgentHttpClient c,
            LocalDispatchStore s,
            DesktopPrintExecutor e,
            long session,
            List<String> printers,
            List<DiscoveredPrinter> discovery) {
        client = c;
        store = s;
        executor = e;
        this.session = session;
        printerNos = List.copyOf(printers);
        this.discovery = List.copyOf(discovery);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private LocalEvent event(LocalDispatch d, QueueObservation observation) {
        return new LocalEvent(
                "AE" + id(),
                d.dispatchKey(),
                d.attemptNo(),
                d.sessionEpoch(),
                d.fenceEpoch(),
                store.nextSequence(d.dispatchKey()),
                observation.status(),
                observation.evidenceLevel(),
                observation.evidence(),
                Instant.now(),
                null);
    }

    public void tick(PrintWorkerBudget budget) {
        var heartbeat =
                client.heartbeat(
                        new DeviceHeartbeatReq(
                                id(), session, List.of(), store.unresolved(100).size(), discovery));
        if (heartbeat.revoked()) throw new IllegalStateException("AGENT_REVOKED");
        flushEvents();
        for (var d : store.unresolved(Math.min(100, budget.maxItems()))) {
            if (inFlight.contains(d.dispatchKey())) {
                if (d.sessionEpoch() == session && d.leaseUntil().isAfter(Instant.now())) renew(d);
                continue;
            }
            if (d.status() == ObservedPrintStatus.RECEIVED
                    && d.sessionEpoch() == session
                    && d.leaseUntil().isAfter(Instant.now())) acceptAndExecute(d);
            else if (d.submittingAt() != null && d.status() != ObservedPrintStatus.UNKNOWN) {
                var observed = executor.query(d);
                store.recordObservationAndAppendEvent(
                        d.dispatchKey(), observed, event(d, observed));
            } else if (d.submittingAt() == null && !d.leaseUntil().isAfter(Instant.now())) {
                var o =
                        new QueueObservation(
                                ObservedPrintStatus.NOT_ACCEPTED,
                                null,
                                true,
                                false,
                                PrintEvidenceLevel.NONE,
                                json.freeze(
                                        Map.of("queueTerminated", true, "partialOutput", false)));
                store.recordObservationAndAppendEvent(d.dispatchKey(), o, event(d, o));
            }
        }
        for (var command : heartbeat.commands()) handle(command);
        var claim = client.claim(new DeviceClaimReq(id(), session, printerNos, 1, 0));
        for (var d : claim.dispatches()) {
            if (d.sessionEpoch() != session
                    || !d.executeBefore().isAfter(Instant.now())
                    || d.cancelRequested()) continue;
            store.receiveAndCommit(d);
            acceptAndExecute(store.find(d.dispatchKey()).orElseThrow());
        }
        flushEvents();
    }

    private void renew(LocalDispatch d) {
        try {
            var grant =
                    client.renew(
                            d.dispatchKey(),
                            new DeviceRenewLeaseReq(id(), session, d.fenceEpoch(), d.leaseToken()));
            var current = store.find(d.dispatchKey()).orElseThrow();
            store.applyLeaseGrant(
                    current.dispatchKey(),
                    current.revision(),
                    grant.leaseToken(),
                    grant.leaseUntil());
        } catch (RuntimeException e) {
            /* Existing send may have reached the queue. It must never be restarted here. */
        }
    }

    private void acceptAndExecute(LocalDispatch d) {
        var accepted =
                client.accept(
                        d.dispatchKey(),
                        new DeviceAcceptDispatchReq(
                                "ACK:" + d.dispatchKey(),
                                session,
                                d.fenceEpoch(),
                                d.leaseToken(),
                                d.documentHash()));
        if (!accepted.accepted()) return;
        var current = store.find(d.dispatchKey()).orElseThrow();
        store.applyLeaseGrant(
                d.dispatchKey(), current.revision(), accepted.leaseToken(), accepted.leaseUntil());
        var ready = store.find(d.dispatchKey()).orElseThrow();
        if (inFlight.add(d.dispatchKey()))
            spool.submit(
                    () -> {
                        try {
                            executeAccepted(ready);
                        } finally {
                            inFlight.remove(d.dispatchKey());
                        }
                    });
    }

    public void executeAccepted(LocalDispatch d) {
        var server = client.getDispatch(d.dispatchKey());
        if (!server.executable()
                || server.dispatch().sessionEpoch() != session
                || server.dispatch().fenceEpoch() != d.fenceEpoch()
                || server.cancelRequested()) return;
        var grant = server.dispatch();
        var current = store.find(d.dispatchKey()).orElseThrow();
        store.applyLeaseGrant(
                current.dispatchKey(), current.revision(), grant.leaseToken(), grant.leaseUntil());
        current = store.find(d.dispatchKey()).orElseThrow();
        var submitting =
                new QueueObservation(
                        ObservedPrintStatus.SUBMITTING,
                        null,
                        false,
                        false,
                        PrintEvidenceLevel.NONE,
                        json.freeze(Map.of("queueTerminated", false, "partialOutput", false)));
        store.markSubmittingAndAppendEvent(
                current.dispatchKey(), current.revision(), event(current, submitting));
        var durable = store.find(d.dispatchKey()).orElseThrow();
        var observation = executor.submit(durable);
        store.recordObservationAndAppendEvent(
                durable.dispatchKey(), observation, event(durable, observation));
    }

    private void flushEvents() {
        var groups = new LinkedHashMap<Long, List<LocalEvent>>();
        for (var e : store.pendingEvents(100))
            groups.computeIfAbsent(e.sessionEpoch(), k -> new ArrayList<>()).add(e);
        for (var entry : groups.entrySet()) {
            var items =
                    entry.getValue().stream()
                            .map(
                                    e ->
                                            new AgentReceiptEvent(
                                                    e.eventNo(),
                                                    e.dispatchKey(),
                                                    e.attemptNo(),
                                                    e.fenceEpoch(),
                                                    e.sequence(),
                                                    e.status(),
                                                    e.evidenceLevel(),
                                                    null,
                                                    e.occurredAt(),
                                                    e.evidence()))
                            .toList();
            var ack = client.report(new DeviceReportEventsReq(entry.getKey(), items));
            var accepted = new HashSet<String>();
            for (var e : ack.events())
                if (Set.of("ACK", "DUPLICATE").contains(e.status())) accepted.add(e.eventNo());
            store.markEventsAcknowledged(accepted);
        }
    }

    private void handle(DeviceCommand c) {
        if (c.expiresAt() == null || c.expiresAt().isBefore(Instant.now())) return;
        if (c.commandType().equals("PROBE")) {
            String name = json.tree(c.payload().canonicalJson()).path("queueName").asText();
            boolean online =
                    Arrays.stream(javax.print.PrintServiceLookup.lookupPrintServices(null, null))
                            .anyMatch(p -> p.getName().equals(name));
            client.reportCommand(
                    c.commandNo(),
                    new DeviceReportCommandResultReq(
                            "CMD:" + c.commandNo(),
                            session,
                            c.printerNo(),
                            c.commandType(),
                            null,
                            null,
                            c.connectionRevision(),
                            null,
                            "APPLIED",
                            json.freeze(Map.of("health", online ? "ONLINE" : "OFFLINE"))));
            return;
        }
        if (c.dispatchKey() == null) return;
        var found = store.find(c.dispatchKey());
        String status = "STALE_TARGET";
        QueueObservation result =
                new QueueObservation(
                        ObservedPrintStatus.UNKNOWN,
                        null,
                        false,
                        false,
                        PrintEvidenceLevel.NONE,
                        json.freeze(Map.of("queueTerminated", false, "partialOutput", false)));
        if (found.isPresent()) {
            var d = found.get();
            if (d.sessionEpoch() == c.sessionEpoch()
                    && d.attemptNo().equals(c.attemptNo())
                    && d.connectionRevision() == c.connectionRevision()
                    && c.fenceEpoch() != null
                    && d.fenceEpoch() == c.fenceEpoch()) {
                var cancelled =
                        new QueueObservation(
                                ObservedPrintStatus.CANCELLED,
                                null,
                                true,
                                false,
                                PrintEvidenceLevel.NONE,
                                json.freeze(
                                        Map.of("queueTerminated", true, "partialOutput", false)));
                if (store.cancelBeforeSubmittingAndAppendEvent(
                        d.dispatchKey(), d.revision(), event(d, cancelled))) {
                    result = cancelled;
                    status = "APPLIED";
                }
                // Losing the SQLite arbitration to SUBMITTING leaves UNKNOWN and retains the device
                // gate.
            }
        }
        client.reportCommand(
                c.commandNo(),
                new DeviceReportCommandResultReq(
                        "CMD:" + c.commandNo(),
                        c.sessionEpoch(),
                        c.printerNo(),
                        c.commandType(),
                        c.dispatchKey(),
                        c.attemptNo(),
                        c.connectionRevision(),
                        c.fenceEpoch(),
                        status,
                        result.evidence()));
    }

    @Override
    public void close() {
        spool.shutdownNow();
    }
}
