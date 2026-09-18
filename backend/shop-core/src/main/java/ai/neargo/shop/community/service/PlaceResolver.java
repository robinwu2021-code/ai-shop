package ai.neargo.shop.community.service;

import ai.neargo.shop.community.entity.GeoPlace;
import ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper;
import ai.neargo.shop.community.support.Geohash;
import ai.neargo.shop.community.support.MapBreaker;
import ai.neargo.shop.spi.platform.GeoPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 坐标 → 「这儿叫什么」。**先问不花钱的。**
 *
 * <p>这一层只管地名。落不落在某个聚落里是 {@code CommunityServiceImpl} 的事，
 * 而且那一档排在这一层**前面** —— 聚落是我们自己维护的业务对象
 * （围栏、商品池、开没开通），没有过期一说，也不该拿「30 天该核对了」去套它。
 *
 * <p>顺序：
 * <ol>
 *   <li>固定地址库命中且没超期 → 直接用，<b>不调地图</b>；</li>
 *   <li>命中但超期 → <b>先用它</b>，把刷新丢到后台。用户在等首页，
 *       不是在等「这个地名是不是最新的」；让正好撞上过期的那个人多等三秒，
 *       而他什么也没做错；</li>
 *   <li>从没见过的格子 → 同步问一次地图 → 落库 → 用。
 *       <b>这是唯一会同步调用地图的情形</b>，随着库长大它越来越少；</li>
 *   <li>地图不可用（没配 key / 熔断 / 额度用完）→ 有旧的就用旧的并标陈旧；</li>
 *   <li>都没有 → 空，调用方退回「只给区县」，<b>不编地名</b>。</li>
 * </ol>
 */
@Service
public class PlaceResolver {

    /** 名字从哪儿来的。与 {@code kind}（名字有多具体）是两件事，必须都给 */
    public static final String SOURCE_PLACE_DB = "PLACE_DB";
    public static final String SOURCE_MAP = "MAP";
    public static final String SOURCE_PLACE_DB_STALE = "PLACE_DB_STALE";

    /**
     * 解析出来的一个地点。
     *
     * @param kind   名字有多具体（POI/AOI/STREET）
     * @param source 名字从哪儿来（库里/地图/库里但陈旧）
     * @param stale  端上据此标「位置可能不是最新的」
     */
    public record Place(String name, String address, String kind, String source, boolean stale) {
    }

    private final GeoPlaceMapper placeMapper;
    /**
     * **跨域只走 spi 的 Port。** 直接注入 platform 域的 Service 会让两个域长在一起，
     * 而且不报错 —— 架构闸门当场抓了这一条。
     */
    private final GeoPort geoPort;
    private final MapBreaker breaker;
    private final int precision;
    private final int freshDays;

    /**
     * 后台刷新。**单线程 + 有界队列 + 满了就丢。**
     *
     * <p>丢掉一次刷新的代价是「这条记录晚几天才更新」，而队列涨起来的代价是
     * 堆内存与线程饥饿 —— 前者无关紧要，后者会把整个服务拖下水。
     * 所以这里刻意选了最没出息的那个策略。
     *
     * <p>没有走 Spring 的 {@code @Async}：本工程没开 {@code @EnableAsync}，
     * 为一处后台刷新去开全局异步会把一堆现有的同步调用变成隐式并发。
     */
    private final ThreadPoolExecutor refresher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "geo-place-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public PlaceResolver(GeoPlaceMapper placeMapper, GeoPort geoPort, MapBreaker breaker,
                         @Value("${shop.geo.place-precision:8}") int precision,
                         @Value("${shop.geo.place-fresh-days:30}") int freshDays) {
        this.placeMapper = placeMapper;
        this.geoPort = geoPort;
        this.breaker = breaker;
        this.precision = precision;
        this.freshDays = freshDays;
    }

    public Optional<Place> resolve(int latE6, int lngE6) {
        String key = Geohash.encode(latE6, lngE6, precision);
        GeoPlace row = placeMapper.selectOne(Wrappers.<GeoPlace>lambdaQuery()
                .eq(GeoPlace::getGeoKey, key).last("limit 1"));

        if (row != null) {
            touch(row);
            if (fresh(row)) {
                return Optional.of(new Place(row.getName(), row.getAddress(), row.getKind(),
                        SOURCE_PLACE_DB, false));
            }
            /*
             * 超期。**先把旧的用上**，刷新丢到后台。
             *
             * 只有在**刷不动的时候**才标陈旧：地图还能用的话，这一条马上就会被
             * 后台刷新，说它「可能不是最新的」是吓人；地图不可用时它可能在那儿
             * 躺很久，那就必须说。
             */
            if (mapUsable()) {
                refresher.execute(() -> refresh(key, latE6, lngE6));
                return Optional.of(new Place(row.getName(), row.getAddress(), row.getKind(),
                        SOURCE_PLACE_DB, false));
            }
            return Optional.of(new Place(row.getName(), row.getAddress(), row.getKind(),
                    SOURCE_PLACE_DB_STALE, true));
        }

        // 从没见过的格子：唯一会同步问地图的地方
        if (mapUsable()) {
            Optional<Place> fromMap = askMap(key, latE6, lngE6);
            if (fromMap.isPresent()) {
                return fromMap;
            }
        }

        /*
         * 地图不可用或这一次没问出来。**再看一眼库里有没有别的格子能顶上**：
         * 这里刻意不做 —— 拿隔壁格子的名字当这里的名字，是编一个地名。
         * 到这一步就老实交给调用方退回「只给区县」。
         */
        return Optional.empty();
    }

    /** 端上看不到这个开关，只看到 {@code source}。运营端看得到，那是唯一能提前发现问题的地方 */
    private boolean mapUsable() {
        return geoPort.available() && !breaker.isOpen();
    }

    private boolean fresh(GeoPlace row) {
        return row.getVerifiedAt() != null
                && row.getVerifiedAt().isAfter(LocalDateTime.now().minusDays(freshDays));
    }

    private Optional<Place> askMap(String key, int latE6, int lngE6) {
        Optional<GeoPort.Reverse> r;
        try {
            r = geoPort.reverse(latE6, lngE6);
        } catch (RuntimeException e) {
            breaker.recordFailure();
            return Optional.empty();
        }
        if (r.isEmpty() || r.get().recommend() == null || r.get().recommend().isBlank()) {
            breaker.recordFailure();
            return Optional.empty();
        }
        breaker.recordSuccess();
        GeoPort.Reverse hit = r.get();
        upsert(key, latE6, lngE6, hit);
        return Optional.of(new Place(hit.recommend(), hit.address(), hit.kind(), SOURCE_MAP, false));
    }

    /** 后台刷新一格。失败就算了 —— 库里那条还在，下一次命中会再排一次 */
    private void refresh(String key, int latE6, int lngE6) {
        if (!mapUsable()) {
            return;
        }
        askMap(key, latE6, lngE6);
    }

    private void upsert(String key, int latE6, int lngE6, GeoPort.Reverse hit) {
        GeoPlace existing = placeMapper.selectOne(Wrappers.<GeoPlace>lambdaQuery()
                .eq(GeoPlace::getGeoKey, key).last("limit 1"));
        if (existing != null) {
            GeoPlace patch = new GeoPlace();
            patch.setId(existing.getId());
            patch.setName(hit.recommend());
            patch.setKind(hit.kind());
            patch.setAddress(hit.address());
            patch.setRegionCode(hit.adcode());
            patch.setTownship(hit.township());
            patch.setVerifiedAt(LocalDateTime.now());
            placeMapper.updateById(patch);
            return;
        }
        GeoPlace row = new GeoPlace();
        row.setGeoKey(key);
        row.setLatE6(latE6);
        row.setLngE6(lngE6);
        row.setName(hit.recommend());
        row.setKind(hit.kind());
        row.setAddress(hit.address());
        row.setRegionCode(hit.adcode());
        row.setTownship(hit.township());
        row.setVerifiedAt(LocalDateTime.now());
        row.setHitCount(1);
        row.setLastHitAt(LocalDateTime.now());
        placeMapper.insert(row);
    }

    /**
     * 记一次命中。**这是「该沉淀成聚落」的唯一依据** ——
     * 用得最多的那些地方值得我们自己认识，之后就再也不依赖地图了。
     */
    private void touch(GeoPlace row) {
        GeoPlace patch = new GeoPlace();
        patch.setId(row.getId());
        patch.setHitCount((row.getHitCount() == null ? 0 : row.getHitCount()) + 1);
        patch.setLastHitAt(LocalDateTime.now());
        placeMapper.updateById(patch);
    }
}
