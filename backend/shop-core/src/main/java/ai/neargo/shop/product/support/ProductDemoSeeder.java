package ai.neargo.shop.product.support;

import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.beans.factory.annotation.Qualifier;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发与测试用的商品演示数据（只在 {@code shop.seed.enabled=true} 时存在）。
 *
 * <p>商品表只由商品领域读写（TDD-商品领域迁到门店交易库 第 4 批）：{@code DevSeeder} 此前直接用商品的实体与 Mapper，
 * 现在只调这里。不走商品服务的建品接口 —— 演示数据要固定编号（G0001、SK0001…，大量测试按编号断言），
 * 而建品接口会生成新编号、还要过门店类目与审核。
 */
@Component
@ConditionalOnProperty(name = "shop.seed.enabled", havingValue = "true")
public class ProductDemoSeeder {

    /** 一个演示 SKU */
    public record SkuSeed(String skuNo, String spec, long price, Long originPrice, int stock) {
    }

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;

    private final StockPort productStock;

    public ProductDemoSeeder(CategoryMapper categoryMapper, GoodsMapper goodsMapper, SkuMapper skuMapper,
                             @Qualifier("stockPortImpl") StockPort productStock) {
        this.categoryMapper = categoryMapper;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.productStock = productStock;
    }

    /**
     * 一个类目。<b>幂等</b>：真库由迁移灌同一批类目，这里只补不跑 Flyway 的 H2 ——
     * 无脑 insert 的结果是真库上主键冲突，而它发生在种子重放阶段，报出来的错看不出跟类目有关。
     *
     * @param qualification 所需资质文案；空 = 无证件要求
     */
    public void seedCategory(String no, String parentNo, int level, String name, String template,
                             String requiredCode, String qualification, int sort, String status) {
        if (categoryMapper.selectCount(Wrappers.<PrdCategory>lambdaQuery().eq(PrdCategory::getCategoryNo, no)) > 0) {
            return;
        }
        var c = new PrdCategory();
        c.setCategoryNo(no);
        c.setParentNo(parentNo);
        c.setLevel(level);
        c.setName(name);
        c.setIcon("");
        c.setSort(sort);
        c.setTemplate(template);
        // 空 = 无门槛。挂了码的类目，商家没拿到对应授权就上不了架
        c.setRequiredCode(requiredCode);
        c.setQualificationRequired(qualification == null ? null : "[\"" + qualification + "\"]");
        c.setStatus(status);
        categoryMapper.insert(c);
    }

    /** 一件在架、已过审的演示商品及其 SKU */
    public void seedGoods(String goodsNo, String merchantNo, String type, String cover, String title,
                          String subtitle, List<SkuSeed> skus) {
        var g = new PrdGoods();
        g.setGoodsNo(goodsNo);
        g.setEntityNo(merchantNo);
        g.setTitle(title);
        g.setSubtitle(subtitle);
        // 演示封面用 emoji：本地联调要的是"一眼能分辨"，而不是四条一模一样的占位
        g.setCover(cover);
        g.setImages("[]");
        g.setType(type);
        /*
         * 无资质门槛的类目，演示商品不该卡在准入上。**类目要与 type 一致**：生鲜挂 CAT120（水果），
         * 其余挂 CAT210（纸品清洁）—— 建库脚本里有一句按类目回填 type 的语句，挂错了生鲜中途会变日用品。
         */
        g.setCategoryNo(PrdGoods.TYPE_FRESH.equals(type) ? "CAT120" : "CAT210");
        g.setFulfillments("[\"STORE_PICKUP\"]");
        g.setSpecGroups("[{\"name\":\"规格\",\"options\":" + optionsJson(skus) + "}]");
        // 商品评分是派生的，不灌一个假的评价数
        g.setRating(50);
        g.setRatingCount(0);
        g.setSales(240);
        g.setLimitPerUser(0);
        g.setOnSale(true);
        g.setAuditStatus(PrdGoods.AUDIT_APPROVED);
        goodsMapper.insert(g);

        for (SkuSeed s : skus) {
            var sku = new PrdSku();
            sku.setSkuNo(s.skuNo());
            sku.setGoodsNo(goodsNo);
            sku.setEntityNo(merchantNo);
            sku.setMarket("CN");
            sku.setOptionValues("[\"" + s.spec() + "\"]");
            sku.setSpec(s.spec());
            sku.setPrice(s.price());
            sku.setOriginPrice(s.originPrice());
            skuMapper.insert(sku);
            productStock.setOnHand(s.skuNo(), null, s.stock(), "OTHER");
        }
    }

    private static String optionsJson(List<SkuSeed> skus) {
        return skus.stream().map(s -> "\"" + s.spec() + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
