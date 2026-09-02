package ai.neargo.shop.marketing.visit.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.visit.StoreVisitService;
import ai.neargo.shop.marketing.visit.entity.MktStoreVisit;
import ai.neargo.shop.marketing.visit.mapper.VisitMappers.StoreVisitMapper;
import ai.neargo.shop.spi.marketing.StoreVisitQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@link StoreVisitQueryPort} 的实现：埋点表的宿主域对外只开这一扇窗。 */
@Component
public class StoreVisitQueryPortImpl implements StoreVisitQueryPort {

    private final StoreVisitMapper visitMapper;
    private final StoreVisitService visitService;

    public StoreVisitQueryPortImpl(StoreVisitMapper visitMapper, StoreVisitService visitService) {
        this.visitMapper = visitMapper;
        this.visitService = visitService;
    }

    /*
     * 薄转发：口径只在 StoreVisitService 一处，这里不重算。
     * 重算一遍就是「同一个指标两个实现」，而它们迟早分岔 —— 分岔那天两个数都看起来对。
     */
    @Override
    public Funnel platformFunnel(long from, long to) {
        StoreVisitService.Funnel f = visitService.platformFunnel(from, to);
        return new Funnel(f.scanUv(), f.enter());
    }

    @Override
    public Map<String, Long> scanCounts(Collection<String> entityNos, long from, long to) {
        if (entityNos == null || entityNos.isEmpty()) {
            return Map.of();
        }
        var w = Wrappers.<MktStoreVisit>query()
                .select("entity_no AS entityNo", "COUNT(*) AS pv")
                .in("entity_no", entityNos)
                .between("at", from, to)
                .groupBy("entity_no");
        // 跨主体只读：解数据域，否则运营看到的只是自己域内那几家
        var rows = DataScopeContext.executeWithoutScope(() -> visitMapper.selectMaps(w));

        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String no = str(r);
            if (no != null) {
                out.put(no, num(r));
            }
        }
        return out;
    }

    @Override
    public ScanCounts scanCountsByStore(Collection<String> entityNos, Collection<String> storeNos,
                                        long from, long to) {
        Map<String, Long> byStore = new LinkedHashMap<>();
        if (storeNos != null && !storeNos.isEmpty()) {
            var w = Wrappers.<MktStoreVisit>query()
                    .select("store_no AS storeNo", "COUNT(*) AS pv")
                    .in("store_no", storeNos)
                    .between("at", from, to)
                    .groupBy("store_no");
            var rows = DataScopeContext.executeWithoutScope(() -> visitMapper.selectMaps(w));
            for (Map<String, Object> r : rows) {
                Object no = cell(r, "storeNo");
                if (no != null) {
                    byStore.put(String.valueOf(no), num(r));
                }
            }
        }

        Map<String, Long> legacy = new LinkedHashMap<>();
        if (entityNos != null && !entityNos.isEmpty()) {
            /*
             * 一店一码之前的行：store_no 为空。**必须显式 isNull 而不是靠 in 落空** ——
             * `in(store_no, ...)` 永远匹配不到 NULL，那部分会静默消失，
             * 表现是老商家的扫码数在升级当天变成 0，而没有任何报错。
             */
            var w = Wrappers.<MktStoreVisit>query()
                    .select("entity_no AS entityNo", "COUNT(*) AS pv")
                    .in("entity_no", entityNos)
                    .isNull("store_no")
                    .between("at", from, to)
                    .groupBy("entity_no");
            var rows = DataScopeContext.executeWithoutScope(() -> visitMapper.selectMaps(w));
            for (Map<String, Object> r : rows) {
                String no = str(r);
                if (no != null) {
                    legacy.put(no, num(r));
                }
            }
        }
        return new ScanCounts(byStore, legacy);
    }

    /*
     * 键**忽略大小写**取：H2 把 `AS entityNo` 折成全小写 entityno，MariaDB 保留写法。
     * 只试原样会在 H2 上取到 null，而后果不报错 —— 页面照常渲染，只是那一列空着。
     * 这个坑在获客看板上已经踩过一次（StoreVisitServiceImpl 里有同样的注释）。
     */
    private static String str(Map<String, Object> row) {
        Object v = cell(row, "entityNo");
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> row) {
        return cell(row, "pv") instanceof Number n ? n.longValue() : 0L;
    }

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
}
