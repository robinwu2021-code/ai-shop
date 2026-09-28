package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.fulfillment.FreightPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 快递运费按模板算（TDD-快递100商家寄件 §8 AC15）。读种子里的默认模板 FT0001，**不改它**：
 * 首重 1000g ¥8、续重每 500g ¥2、满 ¥99 包邮、新疆维吾尔自治区加收 ¥20、西藏自治区不配送。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("快递运费：计价公式、满免、地区加收与不配送")
class FreightTemplateFlowTest {

    @Autowired private FreightPort freightPort;

    @Test
    @DisplayName("★★★ 计价公式：首重内收首重费；超出按续重单位向上取整")
    void formula() {
        assertThat(FreightPort.fee(800, 1000, 800, 500, 200)).isEqualTo(800);
        assertThat(FreightPort.fee(1000, 1000, 800, 500, 200)).isEqualTo(800);
        assertThat(FreightPort.fee(1001, 1000, 800, 500, 200)).as("多 1 克也算一个续重单位").isEqualTo(1000);
        assertThat(FreightPort.fee(1600, 1000, 800, 500, 200)).isEqualTo(1200);
        assertThat(FreightPort.fee(25_000, 1000, 800, 500, 200)).as("25kg 粮油").isEqualTo(800 + 48 * 200);
    }

    @Test
    @DisplayName("★★★ 没填重量的件每件按首重；与填了的叠加")
    void unweighedCountsAsFirstWeight() {
        var q = freightPort.quote(null, 0, 2, 100, "广东省深圳市南山区").orElseThrow();
        assertThat(q.feeMinor()).as("两件没填重量 = 2000g").isEqualTo(800 + 2 * 200);
        q = freightPort.quote(null, 500, 1, 100, "广东省深圳市南山区").orElseThrow();
        assertThat(q.feeMinor()).as("500g + 一件按首重 1000g = 1500g").isEqualTo(800 + 200);
    }

    @Test
    @DisplayName("★★ 满额免邮；地区加收；不配送；地区按地址开头匹配")
    void regionsAndFree() {
        assertThat(freightPort.quote(null, 1000, 0, 9900, "广东省深圳市").orElseThrow().free()).isTrue();
        assertThat(freightPort.quote(null, 1000, 0, 9900, "广东省深圳市").orElseThrow().feeMinor()).isZero();

        var xj = freightPort.quote(null, 1000, 0, 100, "新疆维吾尔自治区乌鲁木齐市天山区").orElseThrow();
        assertThat(xj.feeMinor()).isEqualTo(800 + 2000);
        assertThat(xj.matchedRegion()).isEqualTo("新疆维吾尔自治区");

        assertThat(freightPort.quote(null, 1000, 0, 100, "西藏自治区拉萨市城关区").orElseThrow().rejected()).isTrue();
        assertThat(freightPort.quote(null, 1000, 0, 100, "广东省广州市越秀区新疆维吾尔自治区驻穗办").orElseThrow()
                .matchedRegion()).as("地址中间出现地区名不算命中 —— 只看开头的省份").isNull();
    }

    @Test
    @DisplayName("★ 指定的模板不存在：回落平台默认，不让这一单变成 0 元运费")
    void unknownTemplateFallsBackToDefault() {
        assertThat(freightPort.quote("FT-NOPE", 1000, 0, 100, "广东省").orElseThrow().templateNo()).isEqualTo("FT0001");
    }
}
