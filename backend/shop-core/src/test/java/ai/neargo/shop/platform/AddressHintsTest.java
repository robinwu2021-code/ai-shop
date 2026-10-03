package ai.neargo.shop.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址切分：库内搜索（{@code RegionServiceImpl}）与问地图（{@code inputtips}/{@code around}）
 * 共用同一套切法，这里只守「切出来的城市 hint 与目标关键词对不对」——
 * 库内那边的祖先匹配逻辑已经在 {@code RegionSearchFlowTest} 里守过了。
 */
class AddressHintsTest {

    @Test
    @DisplayName("★★ 「深圳市龙华区福安雅园」→ city=深圳市，target=福安雅园")
    void splitsCityAndTarget() {
        List<String> segs = AddressHints.segments("深圳市龙华区福安雅园");
        assertThat(segs).containsExactly("深圳市", "龙华区", "福安雅园");
        assertThat(AddressHints.cityHint(segs)).isEqualTo("深圳市");
        assertThat(AddressHints.target(segs)).isEqualTo("福安雅园");
    }

    @Test
    @DisplayName("★ 只有目标词、没有行政前缀：city 为 null，target 是整句本身")
    void bareKeywordHasNoCityHint() {
        List<String> segs = AddressHints.segments("福安雅园");
        assertThat(AddressHints.cityHint(segs)).isNull();
        assertThat(AddressHints.target(segs)).isEqualTo("福安雅园");
    }

    @Test
    @DisplayName("★ 「龙华区嘉怡花园」没有市级段：city 为 null（区县不足以定位城市，交给端上已知的定位兜底）")
    void districtOnlyHasNoCityHint() {
        List<String> segs = AddressHints.segments("龙华区嘉怡花园");
        assertThat(AddressHints.cityHint(segs)).isNull();
        assertThat(AddressHints.target(segs)).isEqualTo("嘉怡花园");
    }

    @Test
    @DisplayName("★★ 「社区」「小区」里的「区」不算区县边界 —— 真机撞过：搜官方全名一条也搜不到")
    void doesNotSplitOnCommunityOrEstateSuffix() {
        // 「新苑社区居委会」不该被切成 ["新苑社区","居委会"]（那样库内搜索会把
        // 「新苑社区」当区县去验祖先，验不过，整条搜索白搭）——整句就是一个目标词
        assertThat(AddressHints.segments("新苑社区居委会")).containsExactly("新苑社区居委会");
        assertThat(AddressHints.segments("阳光小区")).containsExactly("阳光小区");
        // 前面真有一个市级段时，后面的「社区/小区」依然不该被拆开
        List<String> segs = AddressHints.segments("深圳市阳光花园小区");
        assertThat(segs).containsExactly("深圳市", "阳光花园小区");
        assertThat(AddressHints.target(segs)).isEqualTo("阳光花园小区");
    }
}
