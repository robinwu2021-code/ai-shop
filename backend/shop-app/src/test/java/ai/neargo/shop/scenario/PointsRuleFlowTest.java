package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCategoryPoints;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.product.PointsRulePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分发放规则的<b>取值优先级</b>：商品例外 → 类目 →（调用方兜底）。
 *
 * <p>本类只测 Port 这一层（「配了什么」）。「没配时用平台兜底」在 settle 域，
 * 与这里是两件事 —— 合起来测的话，一条用例红了分不清是哪一层错。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointsRuleFlowTest {

    @Autowired
    private PointsRulePort pointsRulePort;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private ProductMappers.CategoryPointsMapper categoryPointsMapper;

    @Test
    @DisplayName("★ 两层都没配 → 返回空，由调用方落到平台兜底")
    void nothingConfiguredFallsThrough() {
        String goodsNo = goods("G_PTS_NONE", null);
        assertThat(pointsRulePort.ruleFor(goodsNo, "CAT_PTS_NONE"))
                .as("没配就该返回空 —— 返回一个 0 会让调用方以为『明确要发 0 分』")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 只配了类目 → 用类目的")
    void categoryRuleApplies() {
        String goodsNo = goods("G_PTS_CAT", null);
        category("CAT_PTS_CAT", PrdCategoryPoints.RATIO, 50);   // 千分之五
        assertThat(pointsRulePort.ruleFor(goodsNo, "CAT_PTS_CAT"))
                .get()
                .satisfies(r -> {
                    assertThat(r.mode()).isEqualTo(PointsRulePort.RATIO);
                    assertThat(r.value()).isEqualTo(50);
                });
    }

    @Test
    @DisplayName("★★ 商品例外压过类目 —— 优先级是「取一个值」不是相加")
    void goodsExceptionBeatsCategory() {
        String goodsNo = goods("G_PTS_BOTH", 300);
        category("CAT_PTS_BOTH", PrdCategoryPoints.RATIO, 50);
        assertThat(pointsRulePort.ruleFor(goodsNo, "CAT_PTS_BOTH"))
                .get()
                .satisfies(r -> {
                    assertThat(r.mode()).isEqualTo(PointsRulePort.FIXED);
                    assertThat(r.value()).as("商品配了就用商品的，不与类目相加").isEqualTo(300);
                });
    }

    @Test
    @DisplayName("★★★ 商品例外配 0 → 得到 0，**不掉到类目层**")
    void zeroIsAConfiguredValueNotAnAbsence() {
        /*
         * 这是这类多层配置最常见的 bug，也是本类最重要的一条。
         *
         * 储值卡配 0 分是一个**明确的决定**（充 100 送分等于双倍返利）。
         * 如果实现按「值是不是 0」判有没有配，它就会掉到类目层拿一个非 0 的值，
         * 于是储值卡照发分 —— 而这条路上没有任何报错，账要到对不平那天才发现。
         *
         * 所以判据必须是「**是不是 NULL**」——
         * 而 points_config 是 INT 列，NULL 与 0 天然是两个不同的值，
         * 数据库已经替我们把这件事表达对了。
         */
        String goodsNo = goods("G_PTS_ZERO", 0);
        category("CAT_PTS_ZERO", PrdCategoryPoints.RATIO, 50);
        assertThat(pointsRulePort.ruleFor(goodsNo, "CAT_PTS_ZERO"))
                .get()
                .satisfies(r -> {
                    assertThat(r.mode()).isEqualTo(PointsRulePort.FIXED);
                    assertThat(r.value())
                            .as("配了 0 就是 0；拿到 50 说明把「值为 0」当成了「没配」")
                            .isZero();
                });
    }

    @Test
    @DisplayName("★ NULL 才是「没配」—— 它掉到类目层，0 不掉")
    void nullMeansUnconfiguredZeroDoesNot() {
        /*
         * 与上一条成对：上一条证明「0 不下掉」，这一条证明「NULL 会下掉」。
         * 缺任何一条，实现把两者混为一谈时都可能有一条仍是绿的。
         */
        String goodsNo = goods("G_PTS_NULL", null);
        category("CAT_PTS_NULL", PrdCategoryPoints.FIXED, 20);
        assertThat(pointsRulePort.ruleFor(goodsNo, "CAT_PTS_NULL"))
                .get()
                .satisfies(r -> assertThat(r.value())
                        .as("商品没配（NULL）就该用类目的")
                        .isEqualTo(20));
    }

    @Test
    @DisplayName("★ 不同类目各取各的 —— 多类目子单靠它才不会算成同一个口径")
    void differentCategoriesResolveIndependently() {
        String a = goods("G_PTS_A", null);
        String b = goods("G_PTS_B", null);
        category("CAT_PTS_A", PrdCategoryPoints.FIXED, 10);
        category("CAT_PTS_B", PrdCategoryPoints.FIXED, 999);
        assertThat(pointsRulePort.ruleFor(a, "CAT_PTS_A").get().value()).isEqualTo(10);
        assertThat(pointsRulePort.ruleFor(b, "CAT_PTS_B").get().value())
                .as("两行分属两个类目时必须各取各的 —— 这是按行发放的前提")
                .isEqualTo(999);
    }

    // ── 造数据 ─────────────────────────────────────────────────

    /** 每条用例各自一套编号：这些表是逻辑删除，共用编号 + 先删再插会撞唯一键。 */
    private String goods(String goodsNo, Integer pointsConfig) {
        DataScopeContext.executeWithoutScope(() -> {
            PrdGoods g = new PrdGoods();
            g.setGoodsNo(goodsNo);
            g.setEntityNo("M_PTS_T1");
            g.setTitle("积分规则测试货");
            g.setType("STANDARD");
            g.setPointsConfig(pointsConfig);
            goodsMapper.insert(g);
            return null;
        });
        return goodsNo;
    }

    private void category(String categoryNo, String mode, long value) {
        DataScopeContext.executeWithoutScope(() -> {
            PrdCategoryPoints row = new PrdCategoryPoints();
            row.setCategoryNo(categoryNo);
            row.setEarnMode(mode);
            row.setEarnValue(value);
            categoryPointsMapper.insert(row);
            return null;
        });
    }
}
