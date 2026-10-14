package ai.neargo.shop.logistics.routing;

import ai.neargo.shop.logistics.entity.LgsCarrierCode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.CarrierCodeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@code lgs_carrier_code} 的只读视图（缓存 5 分钟）。
 *
 * <p>整表很小（十几家承运商 × 几个渠道），一次全读进来；运营改了编码最多 5 分钟生效。
 * 每次路由都去查库的话，一次推送要查好几遍同一张表。
 */
@Component
public class CarrierCodeBook {

    static final Duration TTL = Duration.ofMinutes(5);

    private final CarrierCodeMapper mapper;
    private final LongSupplier clock;
    private volatile Snapshot snapshot;

    /**
     * 给 Spring 用的构造器。**必须标 {@code @Autowired}**：本类还有一个给测试注入时钟的构造器，
     * 两个都不标时 Spring 去找无参构造器、整个上下文起不来（2026-10-09 全量 2250 红就是这么来的）。
     */
    @Autowired
    public CarrierCodeBook(CarrierCodeMapper mapper) {
        this(mapper, System::currentTimeMillis);
    }

    CarrierCodeBook(CarrierCodeMapper mapper, LongSupplier clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 我方承运商码 → 该渠道里的叫法 */
    public Optional<String> codeOf(String carrier, String channel) {
        return Optional.ofNullable(current().byCarrier.get(key(carrier, channel)));
    }

    /** 渠道里的叫法 → 我方承运商码（推送回来时反查） */
    public Optional<String> carrierOf(String channel, String code) {
        return code == null ? Optional.empty()
                : Optional.ofNullable(current().byCode.get(key(channel, code.toLowerCase())));
    }

    public boolean covers(String channel, String carrier) {
        return codeOf(carrier, channel).isPresent();
    }

    /** 某渠道覆盖的我方承运商码（运营端渠道总览用） */
    public List<String> carriersOf(String channel) {
        return current().rows.stream()
                .filter(r -> channel.equals(r.getChannel()))
                .map(LgsCarrierCode::getCarrier)
                .sorted()
                .toList();
    }

    /** 运营改了编码后立即失效 */
    public void invalidate() {
        snapshot = null;
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        long now = clock.getAsLong();
        if (s == null || now - s.loadedAt > TTL.toMillis()) {
            s = load(now);
            snapshot = s;
        }
        return s;
    }

    private Snapshot load(long now) {
        List<LgsCarrierCode> rows = mapper.selectList(null);
        Map<String, String> byCarrier = new HashMap<>();
        Map<String, String> byCode = new HashMap<>();
        for (LgsCarrierCode r : rows) {
            byCarrier.put(key(r.getCarrier(), r.getChannel()), r.getCode());
            // 渠道编码大小写不敏感地反查：快递100 推回来的 com 是小写，微信是大写
            byCode.put(key(r.getChannel(), r.getCode().toLowerCase()), r.getCarrier());
        }
        return new Snapshot(now, List.copyOf(rows), byCarrier, byCode);
    }

    private static String key(String a, String b) {
        return a + "|" + (b == null ? "" : b);
    }

    private record Snapshot(long loadedAt, List<LgsCarrierCode> rows,
                            Map<String, String> byCarrier, Map<String, String> byCode) {
    }
}
