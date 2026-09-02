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
        Map<String, long[]> scans = scanGroups(from, to);       // entityNo -> [scan, scanUv]
        Map<String, long[]> funnels = funnelGroups(from, to);   // entityNo -> [enter, register, firstOrder]

        // 两侧主体取并集：只被扫没进店的要出现（那正是「码发了没人进」），
        // 只进店没扫码的也要出现（老客直接从列表进的）
        Set<String> entityNos = new java.util.LinkedHashSet<>(scans.keySet());
        entityNos.addAll(funnels.keySet());
        if (entityNos.isEmpty()) {
            return PageData.of(List.of(), 0, page, size);
        }

        Map<String, MerchantQueryPort.MerchantBrief> briefs = merchantPort.findAll(entityNos);

        List<AcquisitionRow> all = new ArrayList<>();
        for (String no : entityNos) {
            long[] s = scans.getOrDefault(no, new long[]{0, 0});
            long[] f = funnels.getOrDefault(no, new long[]{0, 0, 0});
            var brief = briefs.get(no);
            String name = brief == null ? no : brief.merchantName();
            if (keyword != null && !keyword.isBlank()
                    && !name.contains(keyword) && !no.contains(keyword)) {
                continue;
            }
            // 分母用 UV 不用 PV：同一个人扫三次不该把转化率摊薄成三分之一
            double conv = s[1] == 0 ? 0d : (double) f[2] / s[1];
            all.add(new AcquisitionRow(no, name, s[0], s[1], f[0], f[1], f[2], conv));
        }
        // 扫得多的排前面 —— 看板要先看到「量大但转化差」的那几家
        all.sort((a, b) -> Long.compare(b.scan(), a.scan()));
        return PageData.ofAll(all, page, size);
    }

    @Override
    public Funnel platformFunnel(long from, long to) {
        long scan = 0;
        for (long[] v : scanGroups(from, to).values()) {
            scan += v[0];
        }
        long enter = 0;
        long register = 0;
        long firstOrder = 0;
        for (long[] v : funnelGroups(from, to).values()) {
            enter += v[0];
            register += v[1];
            firstOrder += v[2];
        }
        /*
         * ★ 平台 UV **单独查一次**，不是把各主体的 UV 加起来 ——
         * 同一个人扫了两家店会被算两次，那个数会比真实人数大，且永远查不出为什么。
         */
        return new Funnel(scan, platformScanUv(from, to), enter, register, firstOrder);
    }

    /** entityNo -> [scan(PV), scanUv(按 userNo 回落 deviceId 去重)] */
    private Map<String, long[]> scanGroups(long from, long to) {
        var w = Wrappers.<MktStoreVisit>query()
                // COALESCE：匿名访客没有 userNo，只能按 deviceId 算人
                .select("entity_no AS entityNo", "COUNT(*) AS pv",
                        "COUNT(DISTINCT COALESCE(user_no, device_id)) AS uv")
                .between("at", from, to)
                .groupBy("entity_no");
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : DataScopeContext.executeWithoutScope(() -> visitMapper.selectMaps(w))) {
            out.put(str(r, "entityNo"), new long[]{num(r, "pv"), num(r, "uv")});
        }
        return out;
    }

    private long platformScanUv(long from, long to) {
        var w = Wrappers.<MktStoreVisit>query()
                .select("COUNT(DISTINCT COALESCE(user_no, device_id)) AS uv")
                .between("at", from, to);
        var rows = DataScopeContext.executeWithoutScope(() -> visitMapper.selectMaps(w));
        return rows.isEmpty() ? 0 : num(rows.get(0), "uv");
    }

    /**
     * entityNo -> [enter, register, firstOrder]，全部来自归因留痕。
     *
     * <p>只算 {@code source=STORE_CODE}：获客看板问的是「店铺码带来了什么」，
     * 把邀请与渠道也算进来的话，商家会看到一个自己没法解释的数。
     */
    private Map<String, long[]> funnelGroups(long from, long to) {
        var w = Wrappers.<MktAttributionLog>query()
                .select("entity_no AS entityNo",
                        "COUNT(DISTINCT user_no) AS enterUsers",
                        // CREATED = 此前没有任何归属。**不等于平台新注册**，见 AcquisitionRow 的注释
                        "COUNT(DISTINCT CASE WHEN decision = 'CREATED' THEN user_no END) AS registerUsers",
                        "COUNT(DISTINCT CASE WHEN order_no IS NOT NULL THEN user_no END) AS firstOrderUsers")
                .eq("source", ai.neargo.shop.marketing.attribution.entity.MktAttribution.STORE_CODE)
                .between("at", from, to)
                .groupBy("entity_no");
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : DataScopeContext.executeWithoutScope(() -> logMapper.selectMaps(w))) {
            String no = str(r, "entityNo");
            if (no == null) {
                continue;   // 归因到邀请人/渠道的行没有主体号，不属于任何一家店
            }
            out.put(no, new long[]{num(r, "enterUsers"), num(r, "registerUsers"), num(r, "firstOrderUsers")});
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
