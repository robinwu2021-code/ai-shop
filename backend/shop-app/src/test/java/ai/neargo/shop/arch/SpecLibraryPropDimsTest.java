package ai.neargo.shop.arch;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.service.SpecLibraryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预包装食品合规组维度的种子与下发（TDD-商品属性维度补全 §5）。
 *
 * <p>种子来自 V374，经 gen-test-schema.py 落进 schema-test.sql（测试库不跑 Flyway）。
 * 测三件事：①维度种对了（含新 value_type=TEXT）；②绑到了预包装食品类目；
 * ③`propsForCategory` 把它们连同 valueType 下发到端上 —— valueType 不下发的话，
 * 端上无从知道 TEXT 维度该渲染文本输入，合规字段在建品页上就只是一排点不动的空 chip。
 */
@SpringBootTest
@ActiveProfiles("test")
class SpecLibraryPropDimsTest {

    @Autowired
    private SpecDimMapper dimMapper;
    @Autowired
    private SpecValueMapper valueMapper;
    @Autowired
    private SpecLibraryService specLibraryService;

    private PrdSpecDim dim(String dimNo) {
        return DataScopeContext.executeWithoutScope(() -> dimMapper.selectOne(
                Wrappers.<PrdSpecDim>lambdaQuery().eq(PrdSpecDim::getDimNo, dimNo).last("limit 1")));
    }

    @Test
    @DisplayName("★★ 合规维度已种：配料/厂名厂址/SC/执行标准/净含量为 TEXT，品牌为 ENUM universal")
    void foodComplianceDimsSeeded() {
        for (String no : List.of("SD_INGREDIENTS", "SD_MANUFACTURER", "SD_LICENSE_SC",
                "SD_STANDARD", "SD_NET_CONTENT")) {
            PrdSpecDim d = dim(no);
            assertThat(d).as(no + " 应已种入 prd_spec_dim").isNotNull();
            assertThat(d.getValueType()).as(no + " 应为 TEXT（不入池）").isEqualTo(PrdSpecDim.TEXT);
            assertThat(d.getUsageType()).as(no + " 应为 PROP 不进 SKU").isEqualTo(PrdSpecDim.PROP);
        }
        PrdSpecDim brand = dim("SD_BRAND");
        assertThat(brand).as("品牌应已种").isNotNull();
        assertThat(brand.getValueType()).as("品牌走 ENUM 入池聚合").isEqualTo(PrdSpecDim.ENUM);
        assertThat(brand.getUniversal()).as("品牌 universal：含义跨类目一致").isTrue();
    }

    @Test
    @DisplayName("★★ TEXT 维度不入平台值池 —— prd_spec_value 一行都没有")
    void textDimsHaveNoValues() {
        for (String no : List.of("SD_INGREDIENTS", "SD_MANUFACTURER", "SD_LICENSE_SC",
                "SD_STANDARD", "SD_NET_CONTENT")) {
            long n = DataScopeContext.executeWithoutScope(() -> valueMapper.selectCount(
                    Wrappers.<ai.neargo.shop.product.entity.PrdSpecValue>lambdaQuery()
                            .eq(ai.neargo.shop.product.entity.PrdSpecValue::getDimNo, no)));
            assertThat(n).as(no + " 是 TEXT，不该有任何 prd_spec_value（入池只会污染值库）").isZero();
        }
    }

    @Test
    @DisplayName("★★ 预包装食品类目带出合规参数，且 valueType 随之下发")
    void propsForCategoryCarryValueType() {
        // CAT130 预包装食品：V374 绑了全部 6 项合规维度
        List<SpecTemplateVO> props = specLibraryService.propsForCategory("M_SEED_TEST", "CAT130");
        assertThat(props).as("CAT130 应带出合规参数").isNotEmpty();

        SpecTemplateVO ingredients = props.stream()
                .filter(p -> "SD_INGREDIENTS".equals(p.templateNo())).findFirst().orElse(null);
        assertThat(ingredients).as("配料应出现在 CAT130 的参数候选里").isNotNull();
        assertThat(ingredients.valueType())
                .as("配料的 valueType 要下发到端上，端上据此渲染文本输入而非选值 chip")
                .isEqualTo(PrdSpecDim.TEXT);

        assertThat(props).extracting(SpecTemplateVO::templateNo)
                .as("6 项合规维度都该在 CAT130 带出")
                .contains("SD_BRAND", "SD_NET_CONTENT", "SD_INGREDIENTS",
                        "SD_MANUFACTURER", "SD_LICENSE_SC", "SD_STANDARD");
    }

    @Test
    @DisplayName("★★ 精确产地 SD_ORIGIN_DETAIL 为 TEXT，绑生鲜(CAT120)带出（优化5项 AC3）")
    void originDetailDimSeededAndBound() {
        PrdSpecDim d = dim("SD_ORIGIN_DETAIL");
        assertThat(d).as("原产地维度应已种").isNotNull();
        assertThat(d.getValueType())
                .as("精确产地走 TEXT（省市区级几乎每件唯一，入池只污染）").isEqualTo(PrdSpecDim.TEXT);

        // CAT120 水果：V375 绑了精确产地（与粗粒度 SD_ORIGIN 两层并存）
        List<SpecTemplateVO> props = specLibraryService.propsForCategory("M_SEED_TEST", "CAT120");
        SpecTemplateVO detail = props.stream()
                .filter(p -> "SD_ORIGIN_DETAIL".equals(p.templateNo())).findFirst().orElse(null);
        assertThat(detail).as("CAT120 应带出精确产地").isNotNull();
        assertThat(detail.valueType()).isEqualTo(PrdSpecDim.TEXT);
        // 粗粒度产地仍在：两层并存，不是替换
        assertThat(props).extracting(SpecTemplateVO::templateNo)
                .as("粗产地 SD_ORIGIN 不被替换").contains("SD_ORIGIN");
    }
}
