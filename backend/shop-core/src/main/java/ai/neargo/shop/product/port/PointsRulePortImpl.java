package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategoryPoints;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryPointsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.spi.product.PointsRulePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 积分规则「配了什么」的<b>唯一入口</b>。
 *
 * <p>优先级 <b>商品例外 → 类目</b>，取一个值不是相加。没配返回空，由 settle 兜底。
 *
 * <p>为什么主力是类目而不是商品 —— 依据是实测：线上 199 件商品里，用商品级
 * {@code points_config} 配了积分的是 <b>0 件</b>；而且那一列此前
 * <b>从来没有任何代码读过它</b>，所以 ADR-006 说的「按商品配置」不只是没人填，
 * 是根本没实现。本类是这条规则第一次真正存在。
 */
@Component
public class PointsRulePortImpl implements PointsRulePort {

    private final GoodsMapper goodsMapper;
    private final CategoryPointsMapper categoryPointsMapper;
    public PointsRulePortImpl(GoodsMapper goodsMapper, CategoryPointsMapper categoryPointsMapper) {
        this.goodsMapper = goodsMapper;
        this.categoryPointsMapper = categoryPointsMapper;
    }

    @Override
    public Optional<EarnRule> ruleFor(String goodsNo, String categoryNo) {
        Optional<EarnRule> byGoods = goodsException(goodsNo);
        if (byGoods.isPresent()) {
            return byGoods;
        }
        return categoryRule(categoryNo);
    }

    /**
     * 商品例外（<b>只有运营能配</b>，b 端不给入口）。
     *
     * <p>{@code points_config} 是 {@code INT}：<b>NULL = 没配</b>，有值即固定发这么多分。
     * 于是「配了 0」与「没配」由 <b>NULL 天然区分</b> ——
     * 不需要「键存不存在」那类技巧，数据库替我们把这件事表达对了。
     *
     * <p>储值卡配 0 分是一个明确决定，必须压过类目规则；
     * 按「值是不是 0」判有没有配的话，它会掉到类目层拿一个非 0 的值，
     * 于是储值卡照发分 —— 而这条路上没有任何报错。
     */
    private Optional<EarnRule> goodsException(String goodsNo) {
        if (goodsNo == null || goodsNo.isBlank()) {
            return Optional.empty();
        }
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("LIMIT 1")));
        if (g == null || g.getPointsConfig() == null) {
            return Optional.empty();
        }
        return Optional.of(new EarnRule(FIXED, Math.max(0, g.getPointsConfig())));
    }

    /** 类目规则 —— 主力那一层。 */
    private Optional<EarnRule> categoryRule(String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return Optional.empty();
        }
        PrdCategoryPoints row = DataScopeContext.executeWithoutScope(() ->
                categoryPointsMapper.selectOne(Wrappers.<PrdCategoryPoints>lambdaQuery()
                        .eq(PrdCategoryPoints::getCategoryNo, categoryNo).last("LIMIT 1")));
        if (row == null || row.getEarnValue() == null) {
            return Optional.empty();
        }
        String mode = PrdCategoryPoints.FIXED.equals(row.getEarnMode()) ? FIXED : RATIO;
        return Optional.of(new EarnRule(mode, Math.max(0, row.getEarnValue())));
    }
}
