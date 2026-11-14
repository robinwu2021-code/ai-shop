package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.ReplayVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
import ai.neargo.shop.food.entity.FnbKdsEvent;
import ai.neargo.shop.food.entity.FnbKdsSubscription;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.spi.printing.PrintPort;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Original event log plus revocable device/actor grant. Replay never manufactures events from queue
 * snapshots.
 */
public class FoodKdsServiceImpl implements FoodKdsService {
    private final FoodKdsMapper mapper;
    private final PrintPort printing;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private final Clock clock;

    public FoodKdsServiceImpl(
            FoodKdsMapper mapper,
            PrintPort printing,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.mapper = mapper;
        this.printing = printing;
        this.json = json;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public SubscriptionVO grant(
            String entity, String store, String actor, GrantReq req, String key) {
        scope(entity, store, actor);
        key(key, 128);
        if (req == null) {
            bad();
        }
        id(req.deviceNo());
        id(req.deviceActorNo());
        if (req.stationNos() == null
                || req.stationNos().isEmpty()
                || req.stationNos().size() > 100
                || req.lifetimeSeconds() < 60
                || req.lifetimeSeconds() > 86400) {
            bad();
        }
        req.stationNos().forEach(FoodKdsServiceImpl::id);
        var stations = req.stationNos().stream().distinct().sorted().toList();
        long deviceVersion = liveDevice(entity, store, actor, req.deviceNo(), req.deviceActorNo());
        String hash =
                PricingSnapshotHash.of(
                        List.of(
                                req.deviceNo(),
                                req.deviceActorNo(),
                                stations,
                                req.lifetimeSeconds()));
        return tx(
                () -> {
                    mapper.ensureCursor(entity, store);
                    mapper.head(entity, store, true);
                    var original = mapper.originalGrant(entity, store, actor, key);
                    if (original != null) {
                        if (!hash.equals(original.requestHash())) {
                            conflict();
                        }
                        return view(original);
                    }
                    var row =
                            new FnbKdsSubscription(
                                    BizKey.next("KDS"),
                                    entity,
                                    store,
                                    req.deviceNo(),
                                    req.deviceActorNo(),
                                    json.writeValueAsString(stations),
                                    deviceVersion,
                                    clock.instant().plusSeconds(req.lifetimeSeconds()),
                                    "ACTIVE",
                                    0,
                                    actor,
                                    key,
                                    hash,
                                    null,
                                    null,
                                    null);
                    mapper.insert(row);
                    return view(row);
                });
    }

    @Override
    public SubscriptionVO revoke(
            String entity, String store, String actor, String no, RevokeReq req, String key) {
        scope(entity, store, actor);
        id(no);
        key(key, 128);
        if (req == null || req.version() < 0) {
            bad();
        }
        key(req.reason(), 255);
        return tx(
                () -> {
                    var row = mapper.subscription(entity, store, no, true);
                    if (row == null) {
                        missing();
                    }
                    if ("REVOKED".equals(row.status())) {
                        if (!key.equals(row.revokeKey())
                                || !actor.equals(row.revokedBy())
                                || !req.reason().equals(row.revokeReason())) {
                            conflict();
                        }
                        return view(row);
                    }
                    if (row.version() != req.version()) {
                        conflict();
                    }
                    mapper.revoke(entity, store, no, req.version(), actor, req.reason(), key);
                    return view(mapper.subscription(entity, store, no, false));
                });
    }

    @Override
    public ReplayVO replay(
            String entity, String store, String actor, String no, long after, int limit) {
        scope(entity, store, actor);
        id(no);
        if (after < 0 || limit < 1 || limit > 100) {
            bad();
        }
        var known = mapper.subscription(entity, store, no, false);
        authorize(known, actor);
        // Remote owner checks happen outside the grant row transaction; every batch verifies the
        // actual device binding.
        if (liveDevice(entity, store, actor, known.deviceNo(), actor) != known.deviceVersion()) {
            forbidden();
        }
        return tx(
                () -> {
                    var row = mapper.subscription(entity, store, no, true);
                    authorize(row, actor);
                    long head = mapper.head(entity, store, false);
                    if (after > head) {
                        conflict();
                    }
                    var events = mapper.replay(entity, store, stations(row), after, head, limit);
                    long next = events.size() == limit ? events.getLast().sequenceNo() : head;
                    return new ReplayVO(
                            no,
                            entity,
                            store,
                            events.stream()
                                    .map(
                                            e ->
                                                    new EventVO(
                                                            e.sequenceNo(),
                                                            e.eventNo(),
                                                            e.stationNo(),
                                                            e.eventType(),
                                                            e.sourceNo(),
                                                            e.sourceVersion(),
                                                            e.payload(),
                                                            e.occurredAt().toString()))
                                    .toList(),
                            next,
                            head,
                            next < head);
                });
    }

    @Override
    public void append(EventDraft event) {
        if (event == null) {
            bad();
        }
        scope(event.entityNo(), event.storeNo(), "SYSTEM");
        key(event.eventNo(), 64);
        id(event.sourceNo());
        if (event.stationNo() != null) {
            id(event.stationNo());
        }
        if (!Set.of(
                                "ORDER_ACCEPTED",
                                "KITCHEN_ACTION",
                                "CHECK_CHANGED",
                                "ORDER_CHANGED",
                                "PRINT_CHANGED",
                                "STATION_CONTROL",
                                "VOICE_CALL")
                        .contains(Objects.toString(event.eventType(), ""))
                || event.sourceVersion() < 0
                || event.payload() == null
                || event.payload().length() > 65536) {
            bad();
        }
        try {
            if (!json.readTree(event.payload()).isObject()) {
                bad();
            }
        } catch (RuntimeException failure) {
            bad();
        }
        final Instant at;
        try {
            at = Instant.parse(event.occurredAt()).truncatedTo(ChronoUnit.MILLIS);
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        tx(
                () -> {
                    mapper.ensureCursor(event.entityNo(), event.storeNo());
                    long head = mapper.head(event.entityNo(), event.storeNo(), true);
                    var old =
                            mapper.originalEvent(
                                    event.entityNo(), event.storeNo(), event.eventNo());
                    if (old != null) {
                        if (!Objects.equals(old.stationNo(), event.stationNo())
                                || !old.eventType().equals(event.eventType())
                                || !old.sourceNo().equals(event.sourceNo())
                                || old.sourceVersion() != event.sourceVersion()
                                || !old.payload().equals(event.payload())
                                || !old.occurredAt().equals(at)) {
                            conflict();
                        }
                        return null;
                    }
                    long next = Math.addExact(head, 1);
                    mapper.insert(
                            new FnbKdsEvent(
                                    event.entityNo(),
                                    event.storeNo(),
                                    next,
                                    event.eventNo(),
                                    event.stationNo(),
                                    event.eventType(),
                                    event.sourceNo(),
                                    event.sourceVersion(),
                                    event.payload(),
                                    at));
                    mapper.advance(event.entityNo(), event.storeNo(), next);
                    return null;
                });
    }

    private void authorize(FnbKdsSubscription row, String actor) {
        if (row == null
                || !actor.equals(row.actorNo())
                || !"ACTIVE".equals(row.status())
                || !row.expiresAt().isAfter(clock.instant())) {
            forbidden();
        }
    }

    private long liveDevice(String entity, String store, String actor, String no, String bridge) {
        var configuration = printing.configuration(new PrintPort.Scope(entity, store, actor));
        if (configuration == null) {
            forbidden();
        }
        var device =
                configuration.devices().stream()
                        .filter(
                                d ->
                                        no.equals(d.deviceNo())
                                                && bridge.equals(d.bridgeActorNo())
                                                && "ACTIVE".equals(d.status()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        return device.authorizationVersion();
    }

    private List<String> stations(FnbKdsSubscription row) {
        var tree = json.readTree(row.stations());
        var out = new ArrayList<String>();
        for (var item : tree) {
            out.add(item.asString());
        }
        return List.copyOf(out);
    }

    private SubscriptionVO view(FnbKdsSubscription row) {
        return new SubscriptionVO(
                row.subscriptionNo(),
                row.entityNo(),
                row.storeNo(),
                row.deviceNo(),
                row.actorNo(),
                stations(row),
                row.expiresAt().toString(),
                row.status(),
                row.version());
    }

    private <T> T tx(Supplier<T> body) {
        try {
            return write.execute(tx -> body.get());
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void scope(String entity, String store, String actor) {
        id(entity);
        id(store);
        id(actor);
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,32}")) {
            bad();
        }
    }

    private static void key(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(c -> c < 32)) {
            bad();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }

    private static void forbidden() {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private static void missing() {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
