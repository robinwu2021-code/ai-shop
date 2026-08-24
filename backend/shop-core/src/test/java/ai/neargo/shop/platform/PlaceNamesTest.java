package ai.neargo.shop.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「景滑村委会」与「景滑」是同一个地方 —— 真机上搜「景滑村」出过两条，
 * 根因是 {@code BizRegionController.search()} 判断「官方村是不是已经开通过」
 * 时按原字符串比对，而已开通的聚落存的是商家随手起的名，官方名录里是原始机构名。
 */
class PlaceNamesTest {

    @Test
    @DisplayName("★★ 「景滑村委会」（官方机构名）与「景滑」（商家起的名）归一后相等 —— 真机撞过的那对")
    void officialAndCasualNamesConverge() {
        assertThat(PlaceNames.norm("景滑村委会")).isEqualTo(PlaceNames.norm("景滑"));
    }

    @Test
    @DisplayName("★ 常见的机构名后缀都能被吃掉")
    void stripsOfficialSuffixes() {
        assertThat(PlaceNames.norm("富城村村民委员会")).isEqualTo("富城");
        assertThat(PlaceNames.norm("茜坑社区居委会")).isEqualTo("茜坑");
        assertThat(PlaceNames.norm("阳光花园小区")).isEqualTo("阳光");
        assertThat(PlaceNames.norm("阳光花园")).isEqualTo("阳光");
    }

    @Test
    @DisplayName("★ 括注不算数：「阳光花园(北区)」与「阳光花园」是同一个地方")
    void ignoresParentheticalSuffix() {
        assertThat(PlaceNames.norm("阳光花园(北区)")).isEqualTo(PlaceNames.norm("阳光花园"));
    }
}
