package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.geo.GeoPolygon;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.geo.S2Cover;
import ai.neargo.shop.merchant.entity.MchChannelArea;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.reach.ReachRule.Route;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可见范围判定的<b>唯一入口</b>（ADR-034）。两个方向，共用 {@link ReachRule#decide} 一条规则：
 *
 * <ul>
 *   <li><b>正向</b>（消费者 → 哪些店）：命中走 {@link DbHitFinder} 的索引点查，门店属性走
 *       {@link ReachSnapshotCache}，边界多边形走 {@link PolygonCache}。目录、推荐、详情送达、下单落店都走这里。</li>
 *   <li><b>反向</b>（一家店 → 覆盖哪些小区）：候选上万个，逐个走库不划算，改用
 *       {@link InMemoryHitFinder} 对该店的范围项与网格行做同样的集合成员判断。
 *       B 端「我覆盖哪儿」、保存前预览、运营供给分布走这里。</li>
 * </ul>
 *
 * <p>反向也用<b>网格</b>而不是直接判几何：网格 + 边界精判与几何判定等价
 * （{@code S2CoverTest} 验的那两条蕴含关系），用同一种机制才不会出现「预览说覆盖、买家搜不到」。
 * 预览时那份范围还没落库，所以当场在内存里算网格。
 */
@Service
public class ReachMatcher {

    private final DbHitFinder dbHitFinder;
    private final ReachSnapshotCache snapshotCache;
    private final PolygonCache polygonCache;
    private final ReachGeoProps props;
    private final CommunityQueryPort communityQueryPort;
    private final MerchantMappers.MchEntityMapper entityMapper;
    private final MerchantMappers.MchStoreMapper storeMapper;
    private final MerchantMappers.ServiceAreaMapper areaMapper;
    private final MerchantMappers.ServiceAreaCellMapper cellMapper;
    private final MerchantMappers.FulfillmentChannelMapper channelMapper;
    private final MerchantMappers.ChannelAreaMapper channelAreaMapper;

    public ReachMatcher(DbHitFinder dbHitFinder, ReachSnapshotCache snapshotCache, PolygonCache polygonCache,
                        ReachGeoProps props, CommunityQueryPort communityQueryPort,
                        MerchantMappers.MchEntityMapper entityMapper,
                        MerchantMappers.MchStoreMapper storeMapper,
                        MerchantMappers.ServiceAreaMapper areaMapper,
                        MerchantMappers.ServiceAreaCellMapper cellMapper,
                        MerchantMappers.FulfillmentChannelMapper channelMapper,
                        MerchantMappers.ChannelAreaMapper channelAreaMapper) {
        this.dbHitFinder = dbHitFinder;
        this.snapshotCache = snapshotCache;
        this.polygonCache = polygonCache;
        this.props = props;
        this.communityQueryPort = communityQueryPort;
        this.entityMapper = entityMapper;
        this.storeMapper = storeMapper;
        this.areaMapper = areaMapper;
        this.cellMapper = cellMapper;
        this.channelMapper = channelMapper;
        this.channelAreaMapper = channelAreaMapper;
    }

    // ── 正向：消费者 → 哪些店 ────────────────────────────────────────────

    /**
     * 服务这个消费者的门店，按主体分组。
     *
     * <p>候选 = 命中查询的门店 ∪ 快照里「不限」的门店 —— 后者没有可点查的 ref_code/cell_id，
     * 漏了它，显式选了「全平台不限」的商家（含存量回填那批）对谁都不可见。
     */
    public Map<String, Set<String>> servingStores(ConsumerProfile profile) {
        if (profile == null) {
            return Map.of();
        }
        ReachSnapshot snap = snapshotCache.get();
        Map<String, StoreHits> hits = dbHitFinder.find(profile, null);
        Set<String> candidates = new LinkedHashSet<>(hits.keySet());
        candidates.addAll(snap.unlimitedStores());

        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String storeNo : candidates) {
            StoreMeta meta = snap.meta(storeNo);
            if (meta == null) {
                continue;   // 不是 ACTIVE 主体下的 ACTIVE 门店
            }
            if (ReachRule.decide(meta, hits.get(storeNo), profile, polygonCache)) {
                out.computeIfAbsent(meta.entityNo(), k -> new LinkedHashSet<>()).add(storeNo);
            }
        }
        return out;
    }

    /** 这一家店送不送得到这个消费者。单店判定与目录用的是同一条规则，不会「看得见、下单说不送」 */
    public boolean covers(String storeNo, ConsumerProfile profile) {
        if (storeNo == null || storeNo.isBlank() || profile == null) {
            return false;
        }
        StoreMeta meta = snapshotCache.get().meta(storeNo);
        if (meta == null) {
            return false;
        }
        return ReachRule.decide(meta, dbHitFinder.find(profile, storeNo).get(storeNo), profile, polygonCache);
    }

    /** 结算时这家店的这一路能不能选（自提「全部」不看买家住哪，见 {@link ReachRule#selectable}） */
    public boolean selectable(String storeNo, String channel, ConsumerProfile profile) {
        if (storeNo == null || channel == null || profile == null) {
            return false;
        }
        StoreMeta meta = snapshotCache.get().meta(storeNo);
        if (meta == null) {
            return false;
        }
        StoreHits hits = dbHitFinder.find(profile, storeNo).get(storeNo);
        for (Route r : meta.routes()) {
            if (channel.equals(r.channel())) {
                return ReachRule.selectable(meta, hits, profile, r, polygonCache);
            }
        }
        return false;
    }

    // ── 反向：一家店 → 覆盖哪些小区 ──────────────────────────────────────

    /** 这家店覆盖哪些小区。{@code storeNo} 为空 = 主体口径（名下各 ACTIVE 门店的并集） */
    public List<String> reachableCommunities(MchEntity m, String storeNo) {
        if (storeNo != null && !storeNo.isBlank()) {
            return expand(context(m, storeNo, null));
        }
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (String st : activeStoreNos(m.getEntityNo())) {
            // 主体口径：各店足迹取并集。不能把各店范围混成一份再判 ——
            // A 店排除的楼会把 B 店纳入的同一栋一起减掉
            union.addAll(expand(context(m, st, null)));
        }
        return List.copyOf(union);
    }

    /** 预览：这家店改成这份范围后覆盖哪儿（范围还没落库，网格当场算） */
    public List<String> previewReachable(MchEntity m, String storeNo, List<MchServiceArea> rows) {
        String sNo = storeNo == null || storeNo.isBlank() ? defaultStoreNo(m.getEntityNo()) : storeNo;
        return expand(context(m, sNo, rows == null ? List.of() : rows));
    }

    /** 全平台每家 ACTIVE 门店覆盖哪些小区（运营「供给分布」） */
    public List<StoreCoverageRow> storeCoverage() {
        List<StoreCoverageRow> out = new ArrayList<>();
        for (MchEntity m : activeEntities()) {
            for (String st : activeStoreNos(m.getEntityNo())) {
                out.add(new StoreCoverageRow(m.getEntityNo(), st, expand(context(m, st, null))));
            }
        }
        return out;
    }

    /** 反向展开的一行结果。与 {@code MerchantQueryPort.StoreCoverage} 同形，避免 merchant 域反向依赖 spi 的记录 */
    public record StoreCoverageRow(String entityNo, String storeNo, List<String> communityNos) {
    }

    // ── 展开实现 ──────────────────────────────────────────────────────────

    /** 一家店展开所需的全部材料 */
    private record Context(StoreMeta meta, StoreItems items, Map<String, GeoPolygon> polygons) {
    }

    /**
     * 组装上下文。{@code overrideAreas} 非空 = 预览：用传入的范围行，当场算网格、当场建几何。
     */
    private Context context(MchEntity m, String storeNo, List<MchServiceArea> overrideAreas) {
        if (storeNo == null) {
            // 主体还没有任何门店：没有范围可言（不是「全国」）
            return new Context(new StoreMeta(m.getEntityNo(), null, List.of(), false, false, false, false),
                    new StoreItems(null, List.of(), List.of()), Map.of());
        }
        List<MchServiceArea> areas = overrideAreas != null ? overrideAreas : areasOf(storeNo);
        List<MchFulfillmentChannel> channels = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo())
                        .eq(MchFulfillmentChannel::getStoreNo, storeNo)));
        List<MchChannelArea> subsets = DataScopeContext.executeWithoutScope(() ->
                channelAreaMapper.selectList(Wrappers.<MchChannelArea>lambdaQuery()
                        .eq(MchChannelArea::getStoreNo, storeNo)));

        Map<String, GeoPolygon> polygons = new HashMap<>();
        List<MchServiceAreaCell> cells = new ArrayList<>();
        boolean unlimited = false;
        boolean adminEx = false;
        boolean cmtEx = false;
        boolean polyEx = false;
        for (MchServiceArea a : areas) {
            boolean exclude = MchServiceArea.MODE_EXCLUDE.equals(a.getMode());
            if (!exclude && MchServiceArea.ACTIVE.equals(a.getStatus())
                    && MchServiceArea.LEVEL_UNLIMITED.equals(a.getLevel())) {
                unlimited = true;
            }
            if (exclude) {
                if (MchServiceArea.ADMIN_LEVELS.contains(a.getLevel())) {
                    adminEx = true;
                } else if (MchServiceArea.LEVEL_COMMUNITY.equals(a.getLevel())) {
                    cmtEx = true;
                } else if (MchServiceArea.LEVEL_POLYGON.equals(a.getLevel())) {
                    polyEx = true;
                }
            }
            if (MchServiceArea.LEVEL_POLYGON.equals(a.getLevel()) && a.getGeometry() != null) {
                try {
                    GeoPolygon g = GeoPolygon.parse(a.getGeometry(), props.getPolygonMaxVertices());
                    String areaNo = a.getAreaNo() != null ? a.getAreaNo() : "preview:" + g.fingerprint();
                    polygons.put(areaNo, g);
                    for (S2Cover.Cell c : S2Cover.cover(g, props.getS2MinLevel(), props.getS2MaxLevel(),
                            props.getS2MaxCells())) {
                        MchServiceAreaCell cell = new MchServiceAreaCell();
                        cell.setAreaNo(areaNo);
                        cell.setStoreNo(storeNo);
                        cell.setMode(a.getMode());
                        cell.setCellId(c.token());
                        cell.setS2Level(c.level());
                        cell.setBoundary(c.boundary());
                        cells.add(cell);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 坏几何：这一条不参与展开（与查询侧 PolygonCache 的处理一致）
                }
            }
        }
        // 已落库的多边形直接用库里的网格行，省掉重算；预览那份用上面当场算的
        if (overrideAreas == null) {
            cells = cellsOf(storeNo);
        }
        StoreMeta meta = new StoreMeta(m.getEntityNo(), storeNo,
                StoreRoutes.of(m, storeNo, channels, subsets), unlimited, adminEx, cmtEx, polyEx);
        return new Context(meta, new StoreItems(storeNo, areas, cells), polygons);
    }

    /** 候选 = 全部开放小区 ∪ 范围里点名的小区（它们可能没开放，不在开放全集里），逐个过同一条规则 */
    private List<String> expand(Context ctx) {
        if (ctx.meta().storeNo() == null) {
            return List.of();
        }
        List<CommunityQueryPort.CommunityRef> candidates = new ArrayList<>(communityQueryPort.openCommunityRefs());
        Set<String> seen = new LinkedHashSet<>();
        candidates.forEach(c -> seen.add(c.communityNo()));
        Set<String> named = new LinkedHashSet<>();
        for (MchServiceArea a : ctx.items().areas()) {
            if (MchServiceArea.LEVEL_COMMUNITY.equals(a.getLevel())
                    && MchServiceArea.MODE_INCLUDE.equals(a.getMode())
                    && a.getRefCode() != null && !seen.contains(a.getRefCode())) {
                named.add(a.getRefCode());
            }
        }
        if (!named.isEmpty()) {
            Map<String, CommunityQueryPort.CommunityRef> found = communityQueryPort.communityRefs(named);
            for (String no : named) {
                // 点名了一个库里查不到的小区号：照旧算它，判定只剩「点名」这一条能命中
                candidates.add(found.getOrDefault(no,
                        new CommunityQueryPort.CommunityRef(no, null, null, false)));
            }
        }
        PolygonProbe probe = (areaNo, p) -> {
            GeoPolygon g = ctx.polygons().get(areaNo);
            return g != null && p.hasCoords() && g.covers(p.latE6(), p.lngE6());
        };
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (CommunityQueryPort.CommunityRef c : candidates) {
            ConsumerProfile p = profileOf(c);
            if (ReachRule.decide(ctx.meta(), InMemoryHitFinder.hitsFor(ctx.items(), p), p, probe)) {
                out.add(c.communityNo());
            }
        }
        return List.copyOf(out);
    }

    /** 小区 → 画像。小区自己就是「消费者所在」，带它的区划码、父聚落与坐标 */
    public ConsumerProfile profileOf(CommunityQueryPort.CommunityRef c) {
        return ConsumerProfile.of(c.regionCode(), c.communityNo(), c.parentNo(), c.latE6(), c.lngE6(),
                props.getS2MinLevel(), props.getS2MaxLevel());
    }

    // ── 取数 ──────────────────────────────────────────────────────────────

    private List<MchServiceArea> areasOf(String storeNo) {
        return DataScopeContext.executeWithoutScope(() -> areaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery().eq(MchServiceArea::getStoreNo, storeNo)));
    }

    private List<MchServiceAreaCell> cellsOf(String storeNo) {
        return DataScopeContext.executeWithoutScope(() -> cellMapper.selectList(
                Wrappers.<MchServiceAreaCell>lambdaQuery().eq(MchServiceAreaCell::getStoreNo, storeNo)));
    }

    private List<String> activeStoreNos(String entityNo) {
        return DataScopeContext.executeWithoutScope(() -> storeMapper.selectList(
                        Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, entityNo)
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getStatus,
                                        ai.neargo.shop.merchant.entity.MchStore.ACTIVE)
                                .orderByAsc(ai.neargo.shop.merchant.entity.MchStore::getId)))
                .stream().map(ai.neargo.shop.merchant.entity.MchStore::getStoreNo).toList();
    }

    private String defaultStoreNo(String entityNo) {
        var st = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, entityNo)
                        .orderByDesc(ai.neargo.shop.merchant.entity.MchStore::getIsDefault)
                        .orderByAsc(ai.neargo.shop.merchant.entity.MchStore::getId)
                        .last("limit 1")));
        return st == null ? null : st.getStoreNo();
    }

    private List<MchEntity> activeEntities() {
        return DataScopeContext.executeWithoutScope(() -> entityMapper.selectList(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getStatus, MchEntity.ACTIVE)));
    }
}
