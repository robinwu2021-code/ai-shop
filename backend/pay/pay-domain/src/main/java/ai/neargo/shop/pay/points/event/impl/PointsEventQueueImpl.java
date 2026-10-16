package ai.neargo.shop.pay.points.event.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsEventMapper;
import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

/**
 * Claims use a five-minute lease. A crashed worker is resumed without changing its event identity.
 */
@Service
@Transactional("payTxManager")
public class PointsEventQueueImpl implements PointsEventQueue {
    private final PointsEventMapper mapper;
    private final PointsConfigurationStore config;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PointsEventQueueImpl(
            PointsEventMapper mapper,
            PointsConfigurationStore config,
            ObjectProvider<Clock> clocks) {
        this.mapper = mapper;
        this.config = config;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public void enqueue(String type, String event, Message message) {
        require(
                type != null
                        && !type.isBlank()
                        && event != null
                        && !event.isBlank()
                        && event.length() <= 120
                        && message != null
                        && message.kind() != null
                        && message.payload() != null,
                "POINTS_EVENT_INPUT_INVALID");
        config.lockTypeAndPool(type);
        String body = json.writeValueAsString(message);
        var prior = DataScopeContext.executeWithoutScope(() -> mapper.event(event));
        if (prior != null) {
            require(
                    type.equals(prior.get("type_code")) && body.equals(prior.get("payload_json")),
                    "POINTS_EVENT_CONFLICT");
            return;
        }
        DataScopeContext.executeWithoutScope(
                () -> mapper.insert(type, event, body, clock.millis()));
    }

    @Override
    public Lease claim(long now) {
        return claim(null, now);
    }

    @Override
    public Lease claim(String type, long now) {
        require(now >= 0, "POINTS_EVENT_TIME_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.next(type, now);
                    if (row == null) {
                        return null;
                    }
                    long version = num(row, "version"), id = num(row, "id");
                    int attempt = Math.toIntExact(num(row, "retry_count")) + 1;
                    changed(
                            mapper.transition(
                                    id,
                                    version,
                                    "PROCESSING",
                                    Math.addExact(now, 300000),
                                    attempt));
                    return new Lease(
                            id,
                            version + 1,
                            (String) row.get("type_code"),
                            (String) row.get("event_no"),
                            json.readValue((String) row.get("payload_json"), Message.class),
                            attempt);
                });
    }

    @Override
    public void complete(Lease lease) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.lock(lease.id());
                    checkLease(row, lease);
                    changed(
                            mapper.transition(
                                    lease.id(), lease.version(), "DONE", 0, lease.attempt()));
                    return null;
                });
    }

    @Override
    public void fail(Lease lease, long now, boolean permanent, String reason) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.lock(lease.id());
                    checkLease(row, lease);
                    boolean manual = permanent || lease.attempt() >= 12;
                    long delay = Math.min(3600000, 1000L << Math.min(lease.attempt(), 20));
                    changed(
                            mapper.transition(
                                    lease.id(),
                                    lease.version(),
                                    manual ? "MANUAL" : "RETRY",
                                    Math.addExact(now, delay),
                                    lease.attempt()));
                    audit(
                            lease.typeCode(),
                            "EVENT_FAILURE:" + lease.id() + ":" + lease.version(),
                            Map.of("eventNo", lease.eventNo(), "reason", reason, "manual", manual));
                    return null;
                });
    }

    @Override
    public void retry(long id, String actor, String reason, long now) {
        require(
                actor != null && !actor.isBlank() && reason != null && !reason.isBlank(),
                "POINTS_EVENT_AUDIT_REQUIRED");
        DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.lock(id);
                    require(
                            row != null && "MANUAL".equals(row.get("state")),
                            "POINTS_EVENT_NOT_MANUAL");
                    long version = num(row, "version");
                    changed(mapper.transition(id, version, "RETRY", now, 0));
                    audit(
                            (String) row.get("type_code"),
                            "EVENT_RETRY:" + id + ":" + version,
                            Map.of("actor", actor, "reason", reason));
                    return null;
                });
    }

    private void audit(String type, String event, Object value) {
        String body = json.writeValueAsString(value);
        mapper.audit(type, event, body, PointsDigests.sha256(body));
    }

    private void checkLease(Map<String, Object> row, Lease lease) {
        require(
                row != null
                        && "PROCESSING".equals(row.get("state"))
                        && num(row, "version") == lease.version(),
                "POINTS_EVENT_LEASE_LOST");
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void changed(int rows) {
        require(rows == 1, "POINTS_EVENT_LEASE_LOST");
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
