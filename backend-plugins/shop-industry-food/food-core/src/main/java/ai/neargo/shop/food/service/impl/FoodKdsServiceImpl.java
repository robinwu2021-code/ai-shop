package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodKdsContracts;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;
import ai.neargo.shop.food.entity.FnbKdsSubscription;
import ai.neargo.shop.food.entity.IndStoreDevice;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.support.CommandBusinessKey;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 门店设备与厨显工位（TDD-餐饮域迁移新库 §2.8 D-5）。屏用店员账号登录；连上来的屏要本店在册、在用，
 * 厨显还要设定了工位。停用设备即让它连不上，不另设撤销单。
 */
public class FoodKdsServiceImpl implements FoodKdsService {
    static final String ACTIVE = "ACTIVE";
    static final String SUSPENDED = "SUSPENDED";
    static final String KDS = "KDS";
    static final String CALL_SCREEN = "CALL_SCREEN";
    private final FoodKdsMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private final Clock clock;

    public FoodKdsServiceImpl(
            FoodKdsMapper mapper, ObjectMapper json, PlatformTransactionManager manager, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public StoreDeviceVO register(
            String entity, String store, String actor, RegisterDeviceReq req, String key) {
        scope(entity, store, actor);
        if (req == null
                || !FoodKdsContracts.DEVICE_TYPES.contains(req.deviceType())
                || blank(req.displayName(), 64)
                || (req.serialNo() != null && blank(req.serialNo(), 32))) {
            bad();
        }
        String no = CommandBusinessKey.derive("FDEV", "REGISTER_DEVICE", entity, store, actor, key);
        var row =
                new IndStoreDevice(
                        no, entity, store, req.deviceType(), req.displayName().trim(),
                        req.serialNo() == null ? null : req.serialNo().trim(), ACTIVE, 0);
        // 业务键回查：同一请求键重放得到同一台设备，同键异参冲突。
        Supplier<StoreDeviceVO> replay =
                () -> {
                    var old = mapper.deviceByNo(no);
                    if (old == null
                            || !old.entityNo().equals(entity)
                            || !old.storeNo().equals(store)
                            || !old.deviceType().equals(row.deviceType())
                            || !old.displayName().equals(row.displayName())
                            || !Objects.equals(old.serialNo(), row.serialNo())) {
                        conflict();
                    }
                    return view(old, null);
                };
        if (mapper.deviceByNo(no) != null) return replay.get();
        try {
            return tx(
                    () -> {
                        mapper.insertDevice(row, actor, clock.instant());
                        return view(row, null);
                    });
        } catch (BizException race) {
            if (mapper.deviceByNo(no) != null) return replay.get();
            throw race;
        }
    }

    @Override
    public StoreDeviceVO changeStatus(
            String entity, String store, String actor, String no, DeviceStatusReq req) {
        scope(entity, store, actor);
        id(no);
        if (req == null || req.version() < 0 || !Set.of(ACTIVE, SUSPENDED).contains(req.status())) {
            bad();
        }
        return tx(
                () -> {
                    var row = mapper.device(entity, store, no, true);
                    if (row == null) missing();
                    if (!req.status().equals(row.status())) {
                        if (row.version() != req.version()
                                || mapper.changeStatus(
                                                entity, store, no, req.status(), req.version(), actor,
                                                clock.instant())
                                        != 1) {
                            conflict();
                        }
                        row = mapper.device(entity, store, no, false);
                    }
                    return view(row, mapper.subscription(entity, store, no, false));
                });
    }

    @Override
    public StoreDeviceVO assignStations(
            String entity, String store, String actor, String no, DeviceStationsReq req) {
        scope(entity, store, actor);
        id(no);
        if (req == null
                || req.version() < 0
                || req.stationNos() == null
                || req.stationNos().isEmpty()
                || req.stationNos().size() > 100) {
            bad();
        }
        req.stationNos().forEach(FoodKdsServiceImpl::id);
        var stations = req.stationNos().stream().distinct().sorted().toList();
        String text = json.writeValueAsString(stations);
        return tx(
                () -> {
                    var device = mapper.device(entity, store, no, true);
                    if (device == null) missing();
                    if (!KDS.equals(device.deviceType()) || !ACTIVE.equals(device.status())) conflict();
                    if (mapper.stationCount(entity, store, stations) != stations.size()) bad();
                    var sub = mapper.subscription(entity, store, no, true);
                    var now = clock.instant();
                    if (sub == null) {
                        if (req.version() != 0) conflict();
                        mapper.insertSubscription(entity, store, no, text, actor, now);
                    } else if (!stations.equals(stations(sub))) {
                        if (sub.version() != req.version()
                                || mapper.updateStations(entity, store, no, text, req.version(), actor, now)
                                        != 1) {
                            conflict();
                        }
                    }
                    return view(device, mapper.subscription(entity, store, no, false));
                });
    }

    @Override
    public List<StoreDeviceVO> list(String entity, String store) {
        id(entity);
        id(store);
        Map<String, FnbKdsSubscription> subs =
                mapper.subscriptions(entity, store).stream()
                        .collect(Collectors.toMap(FnbKdsSubscription::deviceNo, Function.identity()));
        return mapper.devices(entity, store).stream()
                .map(d -> view(d, subs.get(d.deviceNo())))
                .toList();
    }

    @Override
    public StoreDeviceVO authorize(String entity, String store, String actor, String no) {
        scope(entity, store, actor);
        id(no);
        var device = mapper.device(entity, store, no, false);
        if (device == null || !ACTIVE.equals(device.status())) forbidden();
        if (CALL_SCREEN.equals(device.deviceType())) return view(device, null);
        if (!KDS.equals(device.deviceType())) forbidden();
        var sub = mapper.subscription(entity, store, no, false);
        if (sub == null) forbidden();
        return view(device, sub);
    }

    private StoreDeviceVO view(IndStoreDevice d, FnbKdsSubscription sub) {
        return new StoreDeviceVO(
                d.deviceNo(),
                d.entityNo(),
                d.storeNo(),
                d.deviceType(),
                d.displayName(),
                d.serialNo(),
                d.status(),
                d.version(),
                sub == null ? List.of() : stations(sub),
                sub == null ? 0 : sub.version());
    }

    private List<String> stations(FnbKdsSubscription row) {
        var out = new ArrayList<String>();
        for (var item : json.readTree(row.stations())) {
            out.add(item.asString());
        }
        return List.copyOf(out);
    }

    private <T> T tx(Supplier<T> body) {
        try {
            return write.execute(tx -> body.get());
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static boolean blank(String value, int max) {
        return value == null
                || value.isBlank()
                || value.trim().length() > max
                || value.chars().anyMatch(c -> c < 32);
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
