package ai.neargo.shop.arch;

import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdCategorySpecValue;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.entity.PrdSpecValue;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecValueMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规格库种子的三条底线（V195/V196）。
 *
 * <p>测的不是「表建对了没有」，而是<b>三件漏了也不报错的事</b>：一个类目配了两个主维度、
 * 绑定指向不存在的维度或值、量纲值没有归一量。共同点是——出事时商家侧只是
 * 「推荐得不对」或「排序有点怪」，没有任何一条异常，而这类问题能在库里躺几个月
 * （老库那两条挂在已归档类目上的模板就是这么留下来的）。
 *
 * <p><b>覆盖率不在这里测。</b>「哪些在售类目还没配规格」取决于运营当下开了哪些类目 ——
 * 那是一个会天天变的运行时事实，不是 CI 能知道的常量。它的守卫在运营端那张
 * 「类目 × 规格」表上：没配的类目标红并计数，缺口每天有人看得见。
 *
 * <p>种子从哪来：测试库走 schema-test.sql 不跑 Flyway，而这一组要测的恰恰是
 * <b>迁移里那份数据</b>本身，所以 schema-test.sql 里带着 V196 的那份种子。
 * <b>不要再加 {@code @Sql} 去灌一遍 V196</b> —— 会撞 uk_spec_dim_no 主键。
 */
@SpringBootTest
@ActiveProfiles("test")
class SpecLibraryCoverageTest {

    @Autowired
    private SpecDimMapper dimMapper;
    @Autowired
    private SpecValueMapper valueMapper;
    @Autowired
    private CategorySpecMapper catSpecMapper;
    @Autowired
    private CategorySpecValueMapper catValueMapper;

    @Test
    @DisplayName("★★ 每个类目恰好一个主维度 —— 零个靠运气预填，两个就是随机预填")
    void exactlyOnePrimaryPerCategory() {
        Map<String, List<PrdCategorySpec>> byCat = activeBindings().stream()
                .collect(Collectors.groupingBy(PrdCategorySpec::getCategoryNo));

        List<String> bad = byCat.entrySet().stream()
                .filter(e -> e.getValue().stream()
                        .filter(b -> Boolean.TRUE.equals(b.getIsPrimary())).count() != 1)
                .map(Map.Entry::getKey)
                .toList();

        assertThat(bad)
                .as("这些类目的主维度不是恰好一个。主维度是「选完类目自动预填哪一组规格」的判据；"
                        + "此前它取决于数据库返回顺序（也就是插入顺序）——"
                        + "一个不该被依赖的巧合，本轮就是来消掉它的")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 绑定与取值子集不许悬空 —— 指向一个不存在的维度，那一组规格会静默消失")
    void noDanglingReferences() {
        Set<String> dims = DIMS().stream().map(PrdSpecDim::getDimNo).collect(Collectors.toSet());
        Set<String> values = VALUES().stream().map(PrdSpecValue::getValueNo).collect(Collectors.toSet());
        List<String> problems = new java.util.ArrayList<>();
        for (PrdCategorySpec b : activeBindings()) {
            if (!dims.contains(b.getDimNo())) {
                problems.add(b.getCategoryNo() + " → 维度 " + b.getDimNo() + "（不存在或已归档）");
            }
        }
        for (PrdCategorySpecValue v : DataScope(() -> catValueMapper.selectList(null))) {
            if (!values.contains(v.getValueNo())) {
                problems.add(v.getCategoryNo() + "/" + v.getDimNo() + " → 值 " + v.getValueNo() + "（不存在）");
            }
        }
        assertThat(problems)
                .as("悬空引用不会抛异常，只会让那一组规格从商家的建品页上安静地消失。"
                        + "老库里那两条挂在已归档类目上的模板就是这么留了几个月的")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 量纲维度的每个值都要有归一量 —— 没有它，1kg 会排在 500g 前面")
    void quantValuesAreNormalised() {
        Set<String> quantDims = DIMS().stream()
                .filter(d -> PrdSpecDim.QUANT.equals(d.getValueType()))
                .map(PrdSpecDim::getDimNo).collect(Collectors.toSet());

        List<String> bad = VALUES().stream()
                .filter(v -> quantDims.contains(v.getDimNo()))
                .filter(v -> v.getNumericValue() == null || v.getNumericUnit() == null)
                .map(v -> v.getValueNo() + "（" + v.getLabel() + "）")
                .toList();

        assertThat(bad)
                .as("这些量纲值没有 numeric_value/unit：按规格排序会退化成字符串序（1kg 排在 500g 前面），"
                        + "「同规格比价」更是无从谈起 —— 而那正是平台维护规格库的理由")
                .isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<PrdCategorySpec> activeBindings() {
        return DataScope(() -> catSpecMapper.selectList(Wrappers.<PrdCategorySpec>lambdaQuery()
                .eq(PrdCategorySpec::getStatus, PrdSpecDim.ACTIVE)));
    }

    private List<PrdSpecDim> DIMS() {
        return DataScope(() -> dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)));
    }

    private List<PrdSpecValue> VALUES() {
        return DataScope(() -> valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                .eq(PrdSpecValue::getStatus, PrdSpecDim.ACTIVE)));
    }

    private static <T> T DataScope(java.util.function.Supplier<T> body) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(body);
    }
}
