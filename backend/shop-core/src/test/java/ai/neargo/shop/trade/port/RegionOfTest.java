package ai.neargo.shop.trade.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收件地址 → 「省 市」。地址快照是<b>不带空格的连写</b>，原来按空格切，
 * 运营端运单列表的「地区」一直是整串地址的前一截（2026-10-09 物流登记场景测试抓到）。
 */
class RegionOfTest {

    @Test
    @DisplayName("★★★ 连写的地址：按行政区划后缀切出省与地级市")
    void compactAddress() {
        assertThat(FulfillmentStatsPortImpl.regionOf("浙江省杭州市西湖区文三路 1 号")).isEqualTo("浙江省 杭州市");
        assertThat(FulfillmentStatsPortImpl.regionOf("广东省深圳市南山区科技园")).isEqualTo("广东省 深圳市");
    }

    @Test
    @DisplayName("★★ 自治区 / 自治州 / 直辖市")
    void specialDivisions() {
        assertThat(FulfillmentStatsPortImpl.regionOf("广西壮族自治区南宁市青秀区民族大道")).isEqualTo("广西壮族自治区 南宁市");
        assertThat(FulfillmentStatsPortImpl.regionOf("四川省凉山彝族自治州西昌市某路")).isEqualTo("四川省 凉山彝族自治州");
        assertThat(FulfillmentStatsPortImpl.regionOf("北京市北京市朝阳区建国路")).isEqualTo("北京市 北京市");
        assertThat(FulfillmentStatsPortImpl.regionOf("上海市浦东新区世纪大道")).isEqualTo("上海市 上海市");
    }

    @Test
    @DisplayName("★ 老的带空格格式照旧认；空值给空串")
    void legacyAndEmpty() {
        assertThat(FulfillmentStatsPortImpl.regionOf("浙江省 杭州市 西湖区 文三路")).isEqualTo("浙江省 杭州市");
        assertThat(FulfillmentStatsPortImpl.regionOf(null)).isEmpty();
        assertThat(FulfillmentStatsPortImpl.regionOf("  ")).isEmpty();
    }
}
