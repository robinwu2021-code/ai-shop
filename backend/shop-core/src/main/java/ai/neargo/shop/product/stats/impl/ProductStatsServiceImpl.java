package ai.neargo.shop.product.stats.impl;

import ai.neargo.shop.product.stats.ProductStatsService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 平台统计的实现。
 *
 * <p><b>整段绕开数据域。</b>这一页问的是「平台一共有多少、用上了多少」——
 * 分母必须是全平台。接上域的话，配了商家域的运营看到的是「他名下那几家的类目使用率」，
 * 而那个数<b>看起来完全正常</b>，只是回答的不是这一页问的问题。
 * 这与链条画像正相反：那一页是一家一行，域该生效。
 *
 * <p>每个指标一条聚合查询，不把行捞出来数。
 */
@Service
public class ProductStatsServiceImpl implements ProductStatsService {

    private final CategoryMapper categories;
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final SpecDimMapper specDims;
    private final CategorySpecMapper categorySpecs;
    private final AuditLogPort auditLogs;

    public ProductStatsServiceImpl(CategoryMapper categories, GoodsMapper goods, SkuMapper skus,
                                   SpecDimMapper specDims, CategorySpecMapper categorySpecs,
                                   AuditLogPort auditLogs) {
        this.categories = categories;
        this.goods = goods;
        this.skus = skus;
        this.specDims = specDims;
        this.categorySpecs = categorySpecs;
        this.auditLogs = auditLogs;
    }

    @Override
    public Stats stats(int auditDays) {
        return DataScopeContext.executeWithoutScope(() -> {
            long categoryTotal = categories.selectCount(Wrappers.<PrdCategory>lambdaQuery()
                    .eq(PrdCategory::getDeleted, 0));
            long categoryUsed = distinct(goods.selectMaps(Wrappers.<PrdGoods>query()
                    .select("COUNT(DISTINCT category_no) AS c")
                    .eq("deleted", 0)
                    .isNotNull("category_no")));

            Map<String, Object> skuRow = one(skus.selectMaps(Wrappers.<PrdSku>query()
                    .select("COUNT(*) AS total",
                            // 空串与 null 都算「没填」—— 表单交空值进来的是空串，
                            // 只判 NULL 的话覆盖率会虚高，而这个数正是扫码功能的天花板
                            "SUM(CASE WHEN barcode IS NOT NULL AND barcode <> '' THEN 1 ELSE 0 END) AS with_barcode",
                            "SUM(CASE WHEN merchant_sku_code IS NOT NULL AND merchant_sku_code <> '' "
                                    + "THEN 1 ELSE 0 END) AS with_code")
                    .eq("deleted", 0)));

            long dimTotal = specDims.selectCount(Wrappers.<PrdSpecDim>lambdaQuery()
                    .eq(PrdSpecDim::getDeleted, 0));
            long dimBound = distinct(categorySpecs.selectMaps(Wrappers.<PrdCategorySpec>query()
                    .select("COUNT(DISTINCT dim_no) AS c")));

            Map<String, Object> auditRow = one(goods.selectMaps(Wrappers.<PrdGoods>query()
                    .select("SUM(CASE WHEN audit_status = 'APPROVED' THEN 1 ELSE 0 END) AS approved",
                            "SUM(CASE WHEN audit_status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected",
                            "SUM(CASE WHEN audit_status = 'AUDITING' THEN 1 ELSE 0 END) AS pending")
                    .eq("deleted", 0)));

            /*
             * 吞吐走审计日志，不是 prd_goods。
             *
             * **表上没有「什么时候审的」这一列** —— 只有 updated_at，而商家改一版
             * 也会动它。拿 updated_at 减 created_at 当「等了多久」会得到一个
             * 看起来很合理、实际在量别的东西的数。审计日志里每次审核都有一行，
             * 那才是这件事发生过的证据。
             *
             * <p>走 {@code AuditLogPort} 而不是直接注 platform 的 mapper：
             * ArchitectureTest 当场拦下了那一版 —— 跨域只走 spi 的 Port，
             * 直接注另一个域的东西会让两个域长在一起，而且不报错。
             */
            long since = System.currentTimeMillis() - auditDays * 86_400_000L;
            long actions = auditLogs.countSince("GOODS_AUDIT", since);

            return new Stats(categoryTotal, categoryUsed,
                    num(skuRow.get("total")), num(skuRow.get("with_barcode")),
                    num(skuRow.get("with_code")),
                    dimTotal, dimBound,
                    num(auditRow.get("approved")), num(auditRow.get("rejected")),
                    num(auditRow.get("pending")),
                    actions, auditDays);
        });
    }

    private static long distinct(List<Map<String, Object>> rows) {
        return num(one(rows).get("c"));
    }

    /** 聚合查询可能返回「一个 null 元素」而不是空列表（全列为 null 的行被映射成 null） */
    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
        return row == null ? Map.of() : row;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
