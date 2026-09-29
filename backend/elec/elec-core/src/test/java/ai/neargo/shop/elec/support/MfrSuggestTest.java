package ai.neargo.shop.elec.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 认不出的厂牌写法 → 建议。建议错了比不给更糟：运营看到建议多半会直接点。 */
class MfrSuggestTest {

    private static final Map<String, String> ALIASES = Map.of(
            "TI", "TI", "TEXASINSTRUMENTS", "TI",
            "ST", "ST", "STMICRO", "ST", "STMICROELECTRONICS", "ST",
            "长电", "CJ");

    @Test
    @DisplayName("★★★ 少一个字母、多一截后缀：互为前缀就给建议")
    void prefixBothWays() {
        assertThat(MfrSuggest.suggest("TEXASINSTRUMENT", ALIASES)).as("别名比它长").isEqualTo("TI");
        assertThat(MfrSuggest.suggest("STMICROELEC", ALIASES)).as("它比别名长").isEqualTo("ST");
        assertThat(MfrSuggest.suggest("长电科技股份", ALIASES)).as("中文两个字就够").isEqualTo("CJ");
    }

    @Test
    @DisplayName("★★★ 两个字母的缩写不许当前缀用：STARCHIP 不是意法，TIANMA 不是德州仪器")
    void shortAliasesNeverPrefixMatch() {
        assertThat(MfrSuggest.suggest("STARCHIP", ALIASES)).isNull();
        assertThat(MfrSuggest.suggest("TIANMA", ALIASES)).isNull();
    }

    @Test
    @DisplayName("★★ 正好就是一条别名：直接给；空写法不给")
    void exactAndEmpty() {
        assertThat(MfrSuggest.suggest("STMICRO", ALIASES)).isEqualTo("ST");
        assertThat(MfrSuggest.suggest("", ALIASES)).isNull();
        assertThat(MfrSuggest.suggest(null, ALIASES)).isNull();
    }

    @Test
    @DisplayName("★★ 几条都对得上时取最长的那条 —— 越长越具体")
    void longestWins() {
        Map<String, String> a = Map.of("ABCD", "X", "ABCDEFG", "Y");
        assertThat(MfrSuggest.suggest("ABCDEFGH", a)).isEqualTo("Y");
    }
}
