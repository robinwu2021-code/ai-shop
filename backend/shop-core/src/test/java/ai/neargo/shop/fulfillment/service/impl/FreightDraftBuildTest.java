package ai.neargo.shop.fulfillment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.fulfillment.dto.FreightTemplateVO.OutOfRangeVO;
import ai.neargo.shop.fulfillment.service.FreightDraftService.ProvincePrice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 从各省报价推模板（TDD-快递100商家寄件 §8 AC17）：众数定首重 / 续重，贵的省加收，查不到的不配送 */
@DisplayName("运费模板草稿：从快递100 各省报价推出首重、续重与地区规则")
class FreightDraftBuildTest {

    @Test
    @DisplayName("★★★ 多数省份的价作首重 / 续重；贵的省份写加收（首重差 + 一个续重单位的差）；查不到的不配送")
    void build() {
        List<ProvincePrice> rows = new ArrayList<>();
        for (String r : FreightDraftServiceImpl.PROVINCES.keySet()) {
            rows.add(switch (r) {
                case "新疆" -> new ProvincePrice(r, 2000L, 1500L);
                case "西藏" -> new ProvincePrice(r, null, null);
                case "海南" -> new ProvincePrice(r, 1000L, 300L);
                default -> new ProvincePrice(r, 600L, 200L);
            });
        }
        var d = FreightDraftServiceImpl.build("山西省运城市盐湖区", "ZTO", 1000, 1000, rows);
        assertThat(d.firstFee()).isEqualTo(600);
        assertThat(d.addFee()).isEqualTo(200);
        assertThat(d.name()).isEqualTo("运城市发 · 中通快递");
        assertThat(d.unquoted()).isEqualTo(1);
        assertThat(d.outOfRange()).containsExactlyInAnyOrder(
                new OutOfRangeVO("新疆", "SURCHARGE", (2000 - 600) + (1500 - 200)),
                new OutOfRangeVO("西藏", "REJECT", 0),
                new OutOfRangeVO("海南", "SURCHARGE", (1000 - 600) + (300 - 200)));
    }

    @Test
    @DisplayName("★ 众数同票取低价 —— 两档各半时按便宜的那档收，贵的那半写成加收")
    void tieTakesLower() {
        List<ProvincePrice> rows = List.of(new ProvincePrice("北京", 800L, 200L), new ProvincePrice("天津", 600L, 200L));
        var d = FreightDraftServiceImpl.build("广东省深圳市", "YTO", 1000, 1000, rows);
        assertThat(d.firstFee()).isEqualTo(600);
        assertThat(d.outOfRange()).containsExactly(new OutOfRangeVO("北京", "SURCHARGE", 200));
    }

    @Test
    @DisplayName("★ 31 个省的简称都能做收货地址前缀（不写「省」字，自治区写简称）")
    void provinceKeysArePrefixes() {
        assertThat(FreightDraftServiceImpl.PROVINCES).hasSize(31);
        FreightDraftServiceImpl.PROVINCES.forEach((k, dest) -> assertThat(dest).startsWith(k));
    }
}
