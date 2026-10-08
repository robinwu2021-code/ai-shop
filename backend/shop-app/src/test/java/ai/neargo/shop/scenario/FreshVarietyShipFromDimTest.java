package ai.neargo.shop.scenario;

import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生鲜补「品种」参数维度。
 *
 * <p>发货地（SD_SHIP_FROM）2026-10-07 建过，2026-10-08 用户取消（V382 解绑停用），
 * 所以这里只验品种。
 *
 * <p>用户 2026-10-07 提了四项，查过现状后只有这两项确实没有 ——
 * 包装 {@code SD_PACK} 与原产地 {@code SD_ORIGIN_DETAIL} 早就存在且已绑生鲜，
 * 不重复造（本轮已经在「单果重量」上误判过一次：提议新增，实际早在表单里）。
 *
 * <p><b>判据查维度的下发结果，不查迁移的 SQL 文本。</b> 查文本的话，
 * 迁移写对了但没被执行（号撞了被跳过、或漏补 schema-test.sql）照样绿 ——
 * 而那正是这道测试要拦的两件事。测试库走 {@code schema-test.sql} 不跑 Flyway，
 * 所以这两处的种子必须一致，少任何一处这里都会红。
 */
@SpringBootTest
@ActiveProfiles("test")
class FreshVarietyShipFromDimTest {

    /** 绑定的四个生鲜类目：蔬菜 / 水果 / 浆果 / 常温水果 */
    private static final List<String> FRESH_CATEGORIES = List.of("CAT110", "CAT120", "CAT121", "CAT122");

    @Autowired
    private SpecDimMapper dimMapper;
    @Autowired
    private CategorySpecMapper catSpecMapper;

    private PrdSpecDim dim(String dimNo) {
        return dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                .eq(PrdSpecDim::getDimNo, dimNo).last("limit 1"));
    }

    private List<PrdCategorySpec> bindings(String dimNo) {
        return catSpecMapper.selectList(Wrappers.<PrdCategorySpec>lambdaQuery()
                .eq(PrdCategorySpec::getDimNo, dimNo));
    }

    @Test
    @DisplayName("★★★ AC1 水果类目能查到「品种」维度，四个生鲜类目都绑上了")
    void varietyDimIsBoundToFreshCategories() {
        PrdSpecDim d = dim("SD_VARIETY");
        assertThat(d).as("SD_VARIETY 维度要存在（迁移 + schema-test.sql 两处都要有）").isNotNull();
        assertThat(d.getName()).isEqualTo("品种");
        // TEXT 不入池：柿子的阳丰、苹果的红富士，按三级类目才穷举得完，而绑定主力 CAT120 是二级
        assertThat(d.getValueType()).as("走 TEXT 不入 prd_spec_value（理由见迁移注释）").isEqualTo("TEXT");

        assertThat(bindings("SD_VARIETY").stream().map(PrdCategorySpec::getCategoryNo))
                .as("蔬菜/水果/浆果/常温水果四个都要绑").containsAll(FRESH_CATEGORIES);
    }


    @Test
    @DisplayName("★★ AC3 两个新维度都不是通用维度 —— 不摆进所有类目的「添加参数」面板")
    void newDimsAreNotUniversal() {
        for (String dimNo : List.of("SD_VARIETY")) {
            assertThat(dim(dimNo).getUniversal())
                    .as("%s 是生鲜语境的字段，universal 必须是 0", dimNo).isFalse();
        }
    }

    @Test
    @DisplayName("★★ AC4 只追加 PROP，不动各类目原有的主维度")
    void newBindingsAreNotPrimary() {
        for (String dimNo : List.of("SD_VARIETY")) {
            for (PrdCategorySpec b : bindings(dimNo)) {
                assertThat(b.getIsPrimary())
                        .as("%s 在 %s 上必须是 is_primary=0（每个类目只能有一个主维度，"
                                + "多一个 SpecLibraryCoverageTest 就红）", dimNo, b.getCategoryNo())
                        .isFalse();
                assertThat(b.getUsageType()).as("%s 要是 PROP 不是 SPEC", dimNo).isEqualTo("PROP");
            }
        }
    }
}
