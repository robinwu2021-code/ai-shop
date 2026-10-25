package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.mapper.FoodStationRuntimeMapper.number;
import static ai.neargo.shop.food.mapper.FoodStationRuntimeMapper.text;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.CommandVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceClaim;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;
import ai.neargo.shop.food.entity.FnbKdsSubscription;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.mapper.FoodStationRuntimeMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.spi.food.DiningStationRuntimeChanged;
import ai.neargo.shop.spi.printing.PrintPort;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Operational pause is authoritative; voice is only played after its fenced hardware receipt. */
public class FoodStationRuntimeServiceImpl implements FoodStationRuntimeService {
    private final FoodStationRuntimeMapper mapper;
    private final FoodKdsMapper kdsMapper;
    private final FoodKdsService kds;
    private final PrintPort printing;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper json;

    public FoodStationRuntimeServiceImpl(
            FoodStationRuntimeMapper mapper,
            FoodKdsMapper kdsMapper,
            FoodKdsService kds,
            PrintPort printing,
            OutboxPublisher outbox,
            PlatformTransactionManager manager,
            Clock clock,
            ObjectMapper json) {
        this.mapper = mapper;
        this.kdsMapper = kdsMapper;
        this.kds = kds;
        this.printing = printing;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.json = json;
    }

    @Override
    public StateVO state(String e, String s, String station) {
        scope(e, s, station);
        if (!mapper.stationExists(e, s, station)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return mapper.state(e, s, station, false);
    }

    @Override
    public void requireRunning(String e, String s, String station) {
        scope(e, s, station);
        write(
                () -> {
                    if (!mapper.stationExists(e, s, station)) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    mapper.initialize(e, s, station);
                    if (!"RUNNING".equals(mapper.state(e, s, station, true).status())) {
                        conflict();
                    }
                    return null;
                });
    }

    @Override
    public CommandVO control(
            String e, String s, String actor, String station, ControlReq req, String key) {
        scope(e, s, station);
        id(actor);
        value(key, 128);
        if (req == null
                || req.version() < 0
                || !Set.of("PAUSE", "RESUME", "CALL")
                        .contains(Objects.toString(req.action(), ""))) {
            bad();
        }
        value(req.reason(), 255);
        if (!mapper.stationExists(e, s, station)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if ("CALL".equals(req.action())) {
            id(req.deviceNo());
            value(req.voiceText(), 128);
            if (printing.configuration(new PrintPort.Scope(e, s, actor)).devices().stream()
                    .noneMatch(
                            d ->
                                    req.deviceNo().equals(d.deviceNo())
                                            && "ACTIVE".equals(d.status()))) {
                forbidden();
            }
        } else if (req.deviceNo() != null || req.voiceText() != null) {
            bad();
        }
        String hash = PricingSnapshotHash.of(List.of(station, req));
        return write(
                () -> {
                    mapper.initialize(e, s, station);
                    var state = mapper.state(e, s, station, true);
                    var old = mapper.original(e, s, actor, key);
                    if (old != null) {
                        if (!hash.equals(text(old, "request_hash"))) {
                            conflict();
                        }
                        return command(old);
                    }
                    if (state.version() != req.version()) {
                        conflict();
                    }
                    long version = Math.addExact(state.version(), 1);
                    mapper.change(
                            e,
                            s,
                            station,
                            "PAUSE".equals(req.action())
                                    ? "PAUSED"
                                    : "RESUME".equals(req.action()) ? "RUNNING" : state.status(),
                            version);
                    String no = BizKey.next("KCMD");
                    mapper.insert(
                            e, s, station, no, req, actor, key, hash, version, clock.instant());
                    var result = command(mapper.command(e, s, no, false));
                    publish(e, s, result);
                    return result;
                });
    }

    @Override
    public VoiceClaim claim(String e, String s, String actor, String subscription) {
        kds.replay(e, s, actor, subscription, 0, 1);
        return write(
                () -> {
                    var grant = grant(e, s, actor, subscription);
                    kdsMapper.ensureCursor(e, s);
                    kdsMapper.head(e, s, true);
                    for (var expired : mapper.expired(e, s, grant.deviceNo(), clock.instant())) {
                        String no = text(expired, "command_no");
                        mapper.receipt(e, s, no, "UNKNOWN", "RECEIPT_TIMEOUT");
                        publish(e, s, command(mapper.command(e, s, no, false)));
                    }
                    if (mapper.outstanding(e, s, grant.deviceNo())) {
                        return null;
                    }
                    var next = mapper.next(e, s, grant.deviceNo(), stations(grant));
                    if (next == null) {
                        return null;
                    }
                    String no = text(next, "command_no"), token = UUID.randomUUID().toString();
                    Instant until = clock.instant().plusSeconds(30);
                    mapper.claim(e, s, no, actor, token, until);
                    publish(e, s, command(mapper.command(e, s, no, false)));
                    return new VoiceClaim(
                            no,
                            text(next, "station_no"),
                            grant.deviceNo(),
                            text(next, "voice_text"),
                            token,
                            until.toString());
                });
    }

    @Override
    public CommandVO acknowledge(
            String e, String s, String actor, String subscription, VoiceReceipt req) {
        if (req == null) {
            bad();
        }
        id(req.commandNo());
        value(req.leaseToken(), 64);
        if (!Set.of("PLAYED", "NOT_PLAYED", "UNKNOWN")
                .contains(Objects.toString(req.status(), ""))) {
            bad();
        }
        if (req.responseCode() != null && !req.responseCode().matches("[A-Z0-9_]{1,64}")) {
            bad();
        }
        kds.replay(e, s, actor, subscription, 0, 1);
        return write(
                () -> {
                    var grant = grant(e, s, actor, subscription);
                    var row = mapper.command(e, s, req.commandNo(), true);
                    if (row == null) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    if (!grant.deviceNo().equals(text(row, "device_no"))
                            || !actor.equals(text(row, "claim_actor"))
                            || !req.leaseToken().equals(text(row, "lease_token"))
                            || !stations(grant).contains(text(row, "station_no"))) {
                        forbidden();
                    }
                    if (Set.of("PLAYED", "NOT_PLAYED").contains(text(row, "status"))) {
                        if (!req.status().equals(text(row, "status"))
                                || !Objects.equals(
                                        req.responseCode(), text(row, "response_code"))) {
                            conflict();
                        }
                        return command(row);
                    }
                    if (!Set.of("SENT", "UNKNOWN").contains(text(row, "status"))) {
                        conflict();
                    }
                    mapper.receipt(e, s, req.commandNo(), req.status(), req.responseCode());
                    var result = command(mapper.command(e, s, req.commandNo(), false));
                    publish(e, s, result);
                    return result;
                });
    }

    private FnbKdsSubscription grant(String e, String s, String actor, String no) {
        var grant = kdsMapper.subscription(e, s, no, true);
        if (grant == null
                || !actor.equals(grant.actorNo())
                || !"ACTIVE".equals(grant.status())
                || !grant.expiresAt().isAfter(clock.instant())) {
            forbidden();
        }
        return grant;
    }

    private List<String> stations(FnbKdsSubscription grant) {
        var result = new ArrayList<String>();
        for (var n : json.readTree(grant.stations())) {
            result.add(n.asString());
        }
        return result;
    }

    private CommandVO command(Map<String, Object> row) {
        return new CommandVO(
                text(row, "command_no"),
                text(row, "station_no"),
                text(row, "action"),
                text(row, "status"),
                number(row, "source_version"),
                text(row, "reason"));
    }

    private void publish(String e, String s, CommandVO cmd) {
        outbox.publish(
                new DiningStationRuntimeChanged(
                        e,
                        s,
                        cmd.stationNo(),
                        cmd.commandNo(),
                        cmd.action(),
                        cmd.sourceVersion(),
                        cmd.status(),
                        cmd.reason(),
                        clock.instant()),
                new PublishOptions("FOOD_STATION:" + cmd.stationNo(), 1));
    }

    private <T> T write(Supplier<T> body) {
        try {
            return tx.execute(t -> body.get());
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void scope(String e, String s, String no) {
        id(e);
        id(s);
        id(no);
    }

    private static void id(String x) {
        if (x == null || !x.matches("[A-Za-z0-9_.:-]{1,32}")) {
            bad();
        }
    }

    private static void value(String x, int max) {
        if (x == null || x.isBlank() || x.length() > max || x.chars().anyMatch(c -> c < 32)) {
            bad();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void forbidden() {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
