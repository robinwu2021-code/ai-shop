package ai.neargo.shop.portal.biz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营范围文字录入 · 拆句判向（TDD-经营范围文字录入 §2）。纯函数，表格驱动。
 *
 * <p>每一行写「店主原话 → 期望的短语与方向」。方向用 + / - 前缀：+ 纳入，- 排除；
 * 多个短语用 | 隔开；开头的 * 表示识别出「不限」。
 */
class ScopeTextParserTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '#', value = {
            // AC1：全国 + 排除
            "全国发货，新疆、西藏不发           # *|-新疆|-西藏",
            "全国包邮 除新疆西藏外             # *|-新疆西藏",
            "除新疆、西藏外全国都发            # *|-新疆|-西藏",
            "新疆西藏青海不发货               # -新疆西藏青海",
            "不限地区；偏远地区不送            # *|-偏远",
            // AC2：纳入，多种分隔
            "龙华区、南山区                  # +龙华区|+南山区",
            "只送龙华区和南山区               # +龙华区|+南山区",
            "深圳龙华区都可以送               # +深圳龙华区",
            "运城市盐湖区全部                 # +运城市盐湖区",
            // 同一句里先纳入后排除：方向按分句判
            "深圳都送，龙华区不送              # +深圳|-龙华区",
            // AC3：楼栋
            "阳光花园3栋不送                  # -阳光花园3栋",
            // 地名里的「发」「外」不能被当虚词剥掉（只从两头剥）
            "发展大道                        # +发展大道",
            "外环街道                        # +外环街道",
    })
    void parses(String text, String expected) {
        var p = ScopeTextParser.parse(text);
        String got = (p.unlimited() ? "*|" : "") + String.join("|", p.phrases().stream()
                .map(ph -> (ph.exclude() ? "-" : "+") + ph.text()).toList());
        String want = expected.trim();
        if (want.startsWith("*") && !want.startsWith("*|")) {
            want = "*|" + want.substring(1);
        }
        assertThat(got).isEqualTo(want.endsWith("|") ? want.substring(0, want.length() - 1) : want);
    }

    @Test
    @DisplayName("空话、只有虚词：什么都不出，不报错")
    void emptyInput() {
        assertThat(ScopeTextParser.parse(null).phrases()).isEmpty();
        assertThat(ScopeTextParser.parse("   ").phrases()).isEmpty();
        assertThat(ScopeTextParser.parse("都可以，，，").phrases()).isEmpty();
    }

    @Test
    @DisplayName("省简称：全称、简称都认；单字不认（「山」不能变成山西）")
    void provinceShortNames() {
        assertThat(ScopeTextParser.provinceCode("新疆")).isEqualTo("65");
        assertThat(ScopeTextParser.provinceCode("新疆维吾尔自治区")).isEqualTo("65");
        assertThat(ScopeTextParser.provinceCode("内蒙古")).isEqualTo("15");
        assertThat(ScopeTextParser.provinceCode("北京")).isEqualTo("11");
        assertThat(ScopeTextParser.provinceCode("山")).isNull();
        assertThat(ScopeTextParser.provinceCode("龙华区")).isNull();
    }

    @Test
    @DisplayName("粘连的几个省按简称切开；切不完整就不切（不留半截）")
    void gluedProvinces() {
        assertThat(ScopeTextParser.splitProvinces("新疆西藏青海")).containsExactly("65", "54", "63");
        assertThat(ScopeTextParser.splitProvinces("新疆维吾尔自治区西藏")).containsExactly("65", "54");
        assertThat(ScopeTextParser.splitProvinces("新疆龙华")).isNull();
        assertThat(ScopeTextParser.splitProvinces("新疆")).as("单个省不算粘连").isNull();
        assertThat(ScopeTextParser.splitProvinces("运城市盐湖区")).isNull();
    }

    @Test
    @DisplayName("方向词剥干净：短语里不残留「不发」「除」「外」")
    void directionWordsStripped() {
        List<String> texts = ScopeTextParser.parse("除了新疆以外，西藏不配送").phrases().stream()
                .map(ScopeTextParser.Phrase::text).toList();
        assertThat(texts).containsExactly("新疆", "西藏");
    }
}
