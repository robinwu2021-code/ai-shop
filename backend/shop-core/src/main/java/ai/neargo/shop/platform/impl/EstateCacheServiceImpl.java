package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.EstateCacheService;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.platform.entity.GeoPoiCache;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.GeoPoiCacheMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import ai.neargo.shop.spi.platform.GeoPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小区清单缓存的落库实现。
 *
 * <p><b>整片覆盖，不做合并</b>：同一个 scopeCode 再抓一次就整块换掉。
 * 合并写法要处理「上一次有、这一次没有」的那些 —— 而地图上消失的小区多半是真的没了
 * （改名、拆迁），留着只会让商家选到一个不存在的地方。
 */
@Service
public class EstateCacheServiceImpl implements EstateCacheService {

    /** 城区：「住宅小区」找楼盘，「小区」补口语叫法 —— 类目收紧到住宅区/住宅小区，精准优先 */
    private static final List<String> URBAN_KEYWORDS = List.of("住宅小区", "小区");
    private static final String URBAN_TYPES = "120300|120302|190108";
    /**
     * 农村：搜「村」。**不能沿用城区那两个词** —— 高德把「住宅小区/小区」归到城镇住宅区
     * 这一类目下，自然村压根不挂这个标签，沿用会让农村这一级永远搜出空列表
     * （真机上撞过：搜「牛杜镇」进「景滑」，下一级内容不对，根子就在这儿）。
     *
     * <p><b>类目也不能沿用城区那份限制</b>：自然村的数据密度本来就比城区小区稀疏，
     * 再叠加「只认住宅区/住宅小区」这道类目闸，搜到的候选会被过滤到接近于零 ——
     * 真机上多个村委会都测出 0 条，这是其中一个可疑成因。农村这条路径不限类目，
     * 让高德自己按关键词相关度给，宁可结果杂一点，也不要杂到全军覆没。
     */
    private static final List<String> RURAL_KEYWORDS = List.of("村");
    /** 圆心搜索半径。街道/社区一片用这个数基本能覆盖，太大了会把邻街的也搜进来 */
    private static final int RADIUS_M = 3000;

    private final GeoPoiCacheMapper mapper;
    private final ObjectMapper json;
    private final GeoService geo;
    private final RegionMapper regionMapper;

    public EstateCacheServiceImpl(GeoPoiCacheMapper mapper, ObjectMapper json, GeoService geo, RegionMapper regionMapper) {
        this.mapper = mapper;
        this.json = json;
        this.geo = geo;
        this.regionMapper = regionMapper;
    }

    @Override
    public Estates get(String scopeCode) {
        if (scopeCode == null || scopeCode.isBlank()) {
            return new Estates("", List.of(), false, false);
        }
        GeoPoiCache row = find(scopeCode);
        if (row == null) {
            return new Estates(scopeCode, List.of(), false, false);
        }
        boolean stale = row.getFetchedAt() == null
                || row.getFetchedAt().isBefore(LocalDateTime.now().minusDays(TTL_DAYS));
        return new Estates(scopeCode, parse(row.getPayload()), true, stale);
    }

    @Override
    public Map<String, Integer> counts(String parentCode) {
        if (parentCode == null || parentCode.isBlank()) {
            return Map.of();
        }
        List<GeoPoiCache> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<GeoPoiCache>lambdaQuery()
                        .select(GeoPoiCache::getScopeCode, GeoPoiCache::getItemCount)
                        .eq(GeoPoiCache::getParentCode, parentCode)
                        .eq(GeoPoiCache::getKind, GeoPoiCache.ESTATE)));
        Map<String, Integer> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(r.getScopeCode(), r.getItemCount() == null ? 0 : r.getItemCount()));
        return out;
    }

    @Override
    public void put(String scopeCode, String parentCode, List<Estate> items) {
        if (scopeCode == null || scopeCode.isBlank() || items == null) {
            return;
        }
        List<Estate> clean = normalize(items);
        GeoPoiCache row = find(scopeCode);
        boolean isNew = row == null;
        if (isNew) {
            row = new GeoPoiCache();
            row.setScopeCode(scopeCode);
            row.setKind(GeoPoiCache.ESTATE);
            row.setSource(GeoPoiCache.AMAP);
        }
        row.setParentCode(parentCode == null ? "" : parentCode);
        row.setPayload(json.writeValueAsString(clean));
        row.setItemCount(clean.size());
        row.setFetchedAt(LocalDateTime.now());
        GeoPoiCache toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                isNew ? mapper.insert(toSave) : mapper.updateById(toSave));
    }

    @Override
    public Estates resolve(String scopeCode, String parentCode, Integer latE6, Integer lngE6,
                           String addressPath, String city, boolean rural) {
        Estates cached = get(scopeCode);
        if (cached.cached() && !cached.stale()) {
            return cached;
        }
        int[] center = center(scopeCode, latE6, lngE6, addressPath, city);
        if (center == null) {
            // 圆心都拿不到（没坐标也没地址、或地图不可用）：缓存里有什么就是什么，刷新不了
            return cached;
        }
        List<Estate> hits = new ArrayList<>();
        String types = rural ? null : URBAN_TYPES;
        for (String kw : rural ? RURAL_KEYWORDS : URBAN_KEYWORDS) {
            geo.around(kw, center[0], center[1], RADIUS_M, types).forEach(t -> hits.add(
                    new Estate(t.name(), t.address(), t.latE6(), t.lngE6(), null)));
        }
        put(scopeCode, parentCode, hits);
        return get(scopeCode);
    }

    /**
     * 圆心：直接给了坐标就用（方案二第一半：有坐标不查）；没有就用地址地理编码一次，
     * 查到了顺手存回 {@code sys_region}（方案二第二半：查到就存，下次不用再查）。
     * 都没有返回 null —— 调用方据此维持旧缓存。
     */
    private int[] center(String regionCode, Integer latE6, Integer lngE6, String addressPath, String city) {
        if (latE6 != null && lngE6 != null) {
            return new int[]{latE6, lngE6};
        }
        if (addressPath == null || addressPath.isBlank() || !geo.available()) {
            return null;
        }
        return geo.geocode(addressPath, city).filter(GeoPort.Geocode::ok)
                .map(g -> {
                    backfillRegionCoords(regionCode, g.latE6(), g.lngE6());
                    return new int[]{g.latE6(), g.lngE6()};
                })
                .orElse(null);
    }

    /**
     * 现查到的坐标存回 {@code sys_region}，仅当这一条本来就没坐标 —— 一条原子 UPDATE，
     * 天然免加锁：两个并发请求都读到 null 都去写，谁先执行谁生效，后到的一条 WHERE 命不中，
     * 影响 0 行。下次同一个区划再被搜到，调用方（端上）会把这个字段原样带回来，
     * 直接命中「有坐标就不查」那一支，不用再打一次高德地理编码。
     *
     * <p>{@code regionCode} 以 "C" 开头是已开通社区的合成缓存键（见端上 {@code estateScope()}），
     * 不是真区划码，跳过 —— 写下去也匹配不到 sys_region 的任何一行。
     */
    private void backfillRegionCoords(String regionCode, int latE6, int lngE6) {
        if (regionCode == null || regionCode.startsWith("C")) {
            return;
        }
        try {
            DataScopeContext.executeWithoutScope(() -> regionMapper.update(null,
                    Wrappers.<SysRegion>lambdaUpdate()
                            .set(SysRegion::getLatE6, latE6)
                            .set(SysRegion::getLngE6, lngE6)
                            // coordsSource/coordsAt 是这张表本来就设计好、但从没被写过的字段
                            // （见 SysRegion 类注释：AMAP 批量补录 / MERCHANT 商家纠正 / OPS 运营录入）——
                            // 这是第一处真正落地的写入，按需查询就该记成 AMAP 这一档
                            .set(SysRegion::getCoordsSource, GeoPoiCache.AMAP)
                            .set(SysRegion::getCoordsAt, LocalDateTime.now())
                            .eq(SysRegion::getRegionCode, regionCode)
                            .isNull(SysRegion::getLatE6)));
        } catch (RuntimeException e) {
            // 写回失败不影响这次请求本身要返回的结果 —— 只是下次还得再查一次，不该让整个接口跟着挂
        }
    }

    /**
     * 同名合并 + 丢掉没坐标的。
     *
     * <p>没坐标的小区进了系统也是坏的：商家选了它，买家用定位永远落不进去 ——
     * 而这件事双方都查不出来。宁可这一片少几条。
     */
    private static List<Estate> normalize(List<Estate> items) {
        Map<String, Estate> byName = new LinkedHashMap<>();
        for (Estate e : items) {
            if (e == null || e.name() == null || e.name().isBlank()) {
                continue;
            }
            if (e.latE6() == null || e.lngE6() == null) {
                continue;
            }
            if (!looksLikeEstate(e.name())) {
                continue;
            }
            byName.putIfAbsent(norm(e.name()), e);
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 名字像不像小区。与端上 `utils/geo.ts` 的 `looksLikeEstate`/`NOT_ESTATE` 同一套规则，
     * 现在挪到服务端：**缓存本身要干净**，不能靠每个读它的客户端各自过滤一遍
     * （过滤规则一旦两边不同步，缓存里存的和界面上看到的就对不上）。
     */
    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile(
            "公交站|地铁站|停车场|超市|便利店|水果|药店|换电|快递|驿站|丰巢|菜鸟|公厕|公共厕所|公园|"
                    + "学校|幼儿园|中学|小学|医院|诊所|卫生|银行|酒店|宾馆|餐厅|饭店|商铺|档口|工业园|"
                    + "办事处|居委会|村委会|工作站|党群|警务|服务中心|充电|社区|街道办|警务室");
    private static final java.util.regex.Pattern ESTATE_SUFFIX = java.util.regex.Pattern.compile(
            "(小区|花园|家园|新村|公寓|苑|园|城|湾|府|庭|邸|里|村|大厦|广场|山庄|名居|世家)$");
    private static final java.util.regex.Pattern TAIL = java.util.regex.Pattern.compile(
            "[A-Za-z0-9一二三四五六七八九十]{0,3}(区|期|栋|号楼)$");

    private static boolean looksLikeEstate(String name) {
        if (NOISE.matcher(name).find()) {
            return false;
        }
        String base = TAIL.matcher(name).replaceAll("");
        return ESTATE_SUFFIX.matcher(base).find() || name.contains("小区");
    }

    /** 与端上、与 from-map 查重同一套归一：「阳光花园」「阳光花园小区」是同一个地方（见 PlaceNames） */
    private static String norm(String s) {
        return ai.neargo.shop.common.PlaceNames.norm(s);
    }

    private List<Estate> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(payload, new TypeReference<List<Estate>>() { });
        } catch (RuntimeException e) {
            // 解不出来当没缓存：缓存坏了不该让这一层打不开，下一次抓取会把它覆盖掉
            return List.of();
        }
    }

    private GeoPoiCache find(String scopeCode) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<GeoPoiCache>lambdaQuery()
                        .eq(GeoPoiCache::getScopeCode, scopeCode)
                        .eq(GeoPoiCache::getKind, GeoPoiCache.ESTATE)
                        .last("LIMIT 1")));
    }
}
