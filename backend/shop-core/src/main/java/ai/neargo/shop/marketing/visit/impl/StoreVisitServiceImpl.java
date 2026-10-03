package ai.neargo.shop.marketing.visit.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionLog;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionLogMapper;
import ai.neargo.shop.marketing.visit.StoreVisitService;
import ai.neargo.shop.marketing.visit.entity.MktStoreVisit;
import ai.neargo.shop.marketing.visit.mapper.VisitMappers.StoreVisitMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 获客漏斗：一次 group by 出扫码，一次 group by 出后三段，再按主体拼起来。
 *
 * <p><b>聚合走 SQL，不照抄本仓现有看板的写法。</b>
 * {@code DashboardServiceImpl} / {@code TradeStatsPortImpl} 是
 * {@code selectList} 全量捞进内存再 stream —— 订单是千级，那样可以。
 * 访问是数量级更高的东西，把明细全量捞进 JVM 只是在等一次 OOM。
 * 这是一处**有意的偏离**，写下来免得下一个人「统一风格」改回去。
 *
 * <p>捞回来的是**聚合后的组**（有访问的主体，几百这个量级），
 * 所以之后按名字过滤、切分页都在内存里做 —— 与商家列表按社区筛同一个理由。
 */
@Service
public class StoreVisitServiceImpl implements StoreVisitService {

    private static final Logger log = LoggerFactory.getLogger(StoreVisitServiceImpl.class);

    private final StoreVisitMapper visitMapper;
    private final AttributionLogMapper logMapper;
    private final MerchantQueryPort merchantPort;

    public StoreVisitServiceImpl(StoreVisitMapper visitMapper, AttributionLogMapper logMapper,
                                 MerchantQueryPort merchantPort) {
        this.visitMapper = visitMapper;
        this.logMapper = logMapper;
        this.merchantPort = merchantPort;
    }

    @Override
    public void record(Visit v) {
        if (v == null || v.entityNo() == null || v.entityNo().isBlank()) {
            return;
        }
        try {
            MktStoreVisit row = new MktStoreVisit();
            row.setVisitNo(BizKey.next(BizKey.STORE_VISIT));
            row.setEntityNo(v.entityNo());
            row.setStoreCode(v.storeCode());
            row.setStoreNo(v.storeNo());
            // 可空是要点：为空就是匿名访客，漏斗第一层靠它区分
            row.setUserNo(v.userNo());
            row.setDeviceId(v.deviceId());
            row.setIp(v.ip());
            row.setUaHash(v.uaHash());
            row.setAt(System.currentTimeMillis());
            row.setTenantNo("MAIN");
            row.setCreatedAt(LocalDateTime.now());
            // 扫码的人不属于任何数据域（他还没登录），写入必须解域
            DataScopeContext.executeWithoutScope(() -> visitMapper.insert(row));
        } catch (RuntimeException e) {
            /*
             * **埋点失败不能变成「扫码进不去店」。**
             * 这个方法挂在扫码后的第一屏上 —— 那一屏失败，商家印出去的贴纸就全废了，
             * 而埋点少一行只是少一行。两者代价不对等，所以这里一定吞。
             */
            log.warn("门店访问埋点写入失败，已忽略：entityNo={}", v.entityNo(), e);
        }
    }

    @Override
    public PageData<AcquisitionRow> acquisition(long from, long to, String keyword, long page, long size) {
        List<Grp> scanGrps = scanGroups(from, to);
        List<Grp> funnelGrps = funnelGroups(from, to);
        if (scanGrps.isEmpty() && funnelGrps.isEmpty()) {
            return PageData.of(List.of(), 0, page, size);
        }

        /*
         * **一行一门店**（S1）。此前一行一主体，多门店商家的几家分店糊成一个数，
         * 而商家问的恰恰是「哪家店的贴纸有用」。
         *
         * 历史行的 store_no 为空（记录在一主体一码的年代）—— 并入该主体的**默认店**，
         * 与旧码本身的去向、与店铺码页的扫码数完全一致。丢掉它们的话，
         * 老商家的获客数会在升级当天归零：数字不见了比数字归错店更难解释。
         */
        Set<String> entityNos = new java.util.LinkedHashSet<>();
        for (Grp g : scanGrps) {
            entityNos.add(g.entityNo());
        }
        for (Grp g : funnelGrps) {
            entityNos.add(g.entityNo());
        }
        Map<String, String> defaultStores = merchantPort.defaultStoreNos(entityNos);

        // key = 门店号；主体连默认店都没有时回落成主体号本身（那一行显示「主体级」）
        Map<String, String> ownerOf = new LinkedHashMap<>();
        Map<String, long[]> scans = new LinkedHashMap<>();
        Map<String, long[]> funnels = new LinkedHashMap<>();
        for (Grp g : scanGrps) {
            String key = keyOf(g, defaultStores);
            ownerOf.put(key, g.entityNo());
            long[] acc = scans.computeIfAbsent(key, k -> new long[2]);
            acc[0] += g.metrics()[0];
            /*
             * <b>UV 只能加，不能去重</b>：并进来的历史行与本店的行是两次分别 COUNT DISTINCT 的结果，
             * 同一个人若两边都出现会被算两次。这里宁可略高也不另查一次 ——
             * 真要精确，得把 store_no 回填到历史埋点上，而那件事本身就是不可知的。
             */
            acc[1] += g.metrics()[1];
        }
        for (Grp g : funnelGrps) {
            String key = keyOf(g, defaultStores);
            ownerOf.put(key, g.entityNo());
            long[] acc = funnels.computeIfAbsent(key, k -> new long[3]);
            acc[0] += g.metrics()[0];
            acc[1] += g.metrics()[1];
            acc[2] += g.metrics()[2];
        }

        Set<String> keys = new java.util.LinkedHashSet<>(scans.keySet());
        keys.addAll(funnels.keySet());
        Map<String, MerchantQueryPort.MerchantBrief> briefs = merchantPort.findAll(entityNos);
        Map<String, String> storeNames = merchantPort.storeNames(keys);

        List<AcquisitionRow> all = new ArrayList<>();
        for (String key : keys) {
            String entityNo = ownerOf.get(key);
            long[] sc = scans.getOrDefault(key, new long[]{0, 0});
            long[] f = funnels.getOrDefault(key, new long[]{0, 0, 0});
            var brief = briefs.get(entityNo);
            String merchantName = brief == null ? entityNo : brief.merchantName();
            // 门店名查不到就给 null，端上显示门店号 —— 别拿主体名冒充店名
            String storeName = storeNames.get(key);
            if (keyword != null && !keyword.isBlank()
                    && !merchantName.contains(keyword) && !entityNo.contains(keyword)
                    && !key.contains(keyword)
                    && (storeName == null || !storeName.contains(keyword))) {
                continue;
            }
            // 分母用 UV 不用 PV：同一个人扫三次不该把转化率摊薄成三分之一
            double conv = sc[1] == 0 ? 0d : (double) f[2] / sc[1];
            all.add(new AcquisitionRow(entityNo, merchantName, key, storeName,
                    sc[0], sc[1], f[0], f[1], f[2], conv));
        }
        // 扫得多的排前面 —— 看板要先看到「量大但转化差」的那几家
        all.sort((a, b) -> Long.compare(b.scan(), a.scan()));
        return PageData.ofAll(all, page, size);
    }

    /** 聚合键：有门店号就用它；历史行并入默认店；连默认店都没有就退回主体号。 */
    private static String keyOf(Grp g, Map<String, String> defaultStores) {
        if (g.storeNo() != null && !g.storeNo().isBlank()) {
            return g.storeNo();
        }
        String def = defaultStores.get(g.entityNo());
        return def != null ? def : g.entityNo();
    }

    @Override
    public Funnel platformFunnel(long from, long to) {
        long scan = 0;
        for (Grp g : scanGroups(from, to)) {
            scan += g.metrics()[0];
        }
        long enter = 0;
        long register = 0;
        long firstOrder = 0;
        for (Grp g : funnelGroups(from, to)) {
            enter += g.metrics()[0];
            register += g.metrics()[1];
            firstOrder += g.metrics()[2];
        }
        /*
         * ★ 平台 UV **单独查一次**，不是把各主体的 UV 加起来 ——
         * 同一个人扫了两家店会被算两次，那个数会比真实人数大，且永远查不出为什么。
         */
        return new Funnel(scan, platformScanUv(from, to), enter, register, firstOrder);
    }

    /**
     * 一组聚合结果：属于哪个主体、哪家门店、几个数。
     *
     * <p>{@code storeNo} 为空 = 一主体一码年代的历史行，物理上分不出分店。
     * 调用方把它们并入该主体的默认店 —— 与旧码本身的去向一致。
     */
    private record Grp(String entityNo, String storeNo, long[] metrics) {
    }

    /** (entityNo, storeNo) -> [scan(PV), scanUv(按 userNo 回落 deviceId 去重)] */
    private List<Grp> scanGroups(long from, long to) {
        var w = Wrappers.<MktStoreVisit>query()
                // COALESCE：匿名访客没有 userNo，只能按 deviceId 算人
                .select("entity_no AS entityNo", "store_no AS storeNo", "COUNT(*) AS pv",
                        "COUNT(DISTINCT COALESCE(user_no, device_id)) AS uv")
                .between("at", from, to)
                .groupBy("entity_no", "store_no");
        /*
         * ★ **接数据域**：配了「只看某商家」的运营，就该只看到那一家的扫码量。
         * 第一版这里解了域（理由写的是「跨主体只读」）—— 那等于让被限定的运营
         * 看到全平台，而且不报错。写入侧仍然解域（见 record）：扫码的人还没登录。
         */
        List<Grp> out = new ArrayList<>();
        for (Map<String, Object> r : visitMapper.selectMaps(w)) {
            out.add(new Grp(str(r, "entityNo"), str(r, "storeNo"),
                    new long[]{num(r, "pv"), num(r, "uv")}));
        }
        return out;
    }

    private long platformScanUv(long from, long to) {
        var w = Wrappers.<MktStoreVisit>query()
                .select("COUNT(DISTINCT COALESCE(user_no, device_id)) AS uv")
                .between("at", from, to);
        var rows = visitMapper.selectMaps(w);
        return rows.isEmpty() ? 0 : num(rows.get(0), "uv");
    }

    /**
     * entityNo -> [enter, register, firstOrder]，全部来自归因留痕。
     *
     * <p>只算 {@code source=STORE_CODE}：获客看板问的是「店铺码带来了什么」，
     * 把邀请与渠道也算进来的话，商家会看到一个自己没法解释的数。
     */
    private List<Grp> funnelGroups(long from, long to) {
        var w = Wrappers.<MktAttributionLog>query()
                .select("entity_no AS entityNo", "store_no AS storeNo",
                        "COUNT(DISTINCT user_no) AS enterUsers",
                        // CREATED = 此前没有任何归属。**不等于平台新注册**，见 AcquisitionRow 的注释
                        "COUNT(DISTINCT CASE WHEN decision = 'CREATED' THEN user_no END) AS registerUsers",
                        "COUNT(DISTINCT CASE WHEN order_no IS NOT NULL THEN user_no END) AS firstOrderUsers")
                .eq("source", ai.neargo.shop.marketing.attribution.entity.MktAttribution.STORE_CODE)
                .between("at", from, to)
                .groupBy("entity_no", "store_no");
        List<Grp> out = new ArrayList<>();
        for (Map<String, Object> r : logMapper.selectMaps(w)) {
            String no = str(r, "entityNo");
            if (no == null) {
                continue;   // 归因到邀请人/渠道的行没有主体号，不属于任何一家店
            }
            out.add(new Grp(no, str(r, "storeNo"),
                    new long[]{num(r, "enterUsers"), num(r, "registerUsers"), num(r, "firstOrderUsers")}));
        }
        return out;
    }

    /**
     * {@code selectMaps} 的键**大小写随数据库而变**：H2 把 {@code AS entityNo}
     * 原样折成全小写 {@code entityno}，MariaDB 则保留写法。
     *
     * <p>所以这里**忽略大小写取值**，不是「试一次原样再试一次大写」——
     * 那样写在 H2 上会取到 null，而后果不是报错：
     * 看板照常返回一行，只是 {@code merchantNo} 与 {@code merchantName} 都是 null，
     * 数字还都是对的。这一条正是这么被抓出来的。
     */
    private static Object cell(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = cell(row, key);
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> row, String key) {
        return cell(row, key) instanceof Number n ? n.longValue() : 0L;
    }
}
