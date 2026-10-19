package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsFactMapper;
import ai.neargo.shop.trade.order.points.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class OrderPointsFactServiceImpl implements OrderPointsFactService {
    private final OrderPointsFactMapper mapper;
    private final OrderPointsBindingService bindings;
    private final OutboxEventBus outbox;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsFactServiceImpl(
            OrderPointsFactMapper mapper,
            OrderPointsBindingService bindings,
            OutboxEventBus outbox) {
        this.mapper = mapper;
        this.bindings = bindings;
        this.outbox = outbox;
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public PointsOrderEvent append(PointsOrderEvent draft) {
        require(draft != null && draft.fact().sequence() == 0, "POINTS_SOURCE_DRAFT_REQUIRED");
        var b = bindings.find(draft.subOrderNo());
        require(b != null, "POINTS_BINDING_UNKNOWN");
        var a = b.admission();
        require(
                a.orderNo().equals(draft.orderNo())
                        && a.storeNo().equals(draft.storeNo())
                        && a.merchantNo().equals(draft.merchantNo())
                        && a.userNo().equals(draft.userNo())
                        && a.currency().equals(draft.currency())
                        && a.typeCode().equals(draft.typeCode()),
                "POINTS_SOURCE_BINDING_CONFLICT");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    mapper.initialize(draft.typeCode());
                    long last = mapper.lockCounter(draft.typeCode());
                    String normalized = json.writeValueAsString(draft);
                    String hash = hash(normalized);
                    require(normalized.length() <= 1000000, "POINTS_SOURCE_PAYLOAD_TOO_LARGE");
                    var prior = mapper.find(draft.eventNo());
                    if (prior != null) {
                        require(
                                hash.equals(prior.get("normalized_hash")),
                                "POINTS_SOURCE_EVENT_CONFLICT");
                        return json.readValue(
                                (String) prior.get("payload_json"), PointsOrderEvent.class);
                    }
                    long sequence = Math.addExact(last, 1);
                    var f = draft.fact();
                    var frozen =
                            new PointsOrderEvent(
                                    draft.schemaVersion(),
                                    draft.orderNo(),
                                    draft.subOrderNo(),
                                    draft.merchantNo(),
                                    draft.storeNo(),
                                    draft.userNo(),
                                    draft.typeCode(),
                                    draft.currency(),
                                    draft.sourceVersion(),
                                    draft.occurredAt(),
                                    new SourceFactsPort.Fact(
                                            sequence,
                                            f.lifecycle(),
                                            f.earning(),
                                            f.closure(),
                                            f.zeroCash(),
                                            f.refund()));
                    mapper.advance(draft.typeCode(), sequence);
                    boolean terminal =
                            f.closure() != null
                                    || (f.lifecycle() != null
                                            && f.lifecycle().kind()
                                                    == PointsFactPort.FactKind.CANCELLED);
                    mapper.insert(
                            draft.eventNo(),
                            draft.typeCode(),
                            sequence,
                            draft.storeNo(),
                            draft.subOrderNo(),
                            json.writeValueAsString(frozen),
                            hash,
                            terminal);
                    outbox.publish(new OrderPointsSourceEvent(draft.eventNo(), draft.subOrderNo()));
                    return frozen;
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public Optional<PointsOrderEvent> find(String event) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.find(event);
                    return row == null
                            ? Optional.empty()
                            : Optional.of(
                                    json.readValue(
                                            (String) row.get("payload_json"),
                                            PointsOrderEvent.class));
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public SourceFactsPort.Page scan(String type, long after, int limit) {
        require(
                type != null && !type.isBlank() && after >= 0 && limit >= 1 && limit <= 200,
                "POINTS_SOURCE_SCAN_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var facts =
                            mapper.scan(type, after, limit).stream()
                                    .map(v -> json.readValue(v, PointsOrderEvent.class).fact())
                                    .toList();
                    return new SourceFactsPort.Page(
                            facts.isEmpty() ? after : facts.getLast().sequence(), facts);
                });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    public void acknowledge(String event) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    require(mapper.acknowledge(event) == 1, "POINTS_SOURCE_EVENT_UNKNOWN");
                    return null;
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public boolean settledSubOrder(String sub) {
        return DataScopeContext.executeWithoutScope(
                () -> mapper.pendingSub(sub) == 0 && mapper.terminalSub(sub) > 0);
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public boolean settled(String store) {
        return DataScopeContext.executeWithoutScope(
                () -> mapper.pendingStore(store) == 0 && mapper.unclosedSource(store) == 0);
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public List<String> pending(int limit) {
        require(limit >= 1 && limit <= 200, "POINTS_SOURCE_SCAN_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.pending(limit));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean v, String code) {
        if (!v) throw new IllegalArgumentException(code);
    }
}
