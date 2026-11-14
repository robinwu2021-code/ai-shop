package ai.neargo.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 快递公司按市场分表（TDD-商户App快递公司 E2、E3）。 */
class ExpressCompaniesTest {

    @Test
    @DisplayName("★★★ 台湾市场只给台湾承运商，黑貓第一（App 默认选第一项）")
    void tw_market_lists_tw_carriers_only() {
        assertThat(ExpressCompanies.forMarket("TW")).extracting(ExpressCompanies.Company::code)
                .containsExactly("TCAT", "HCT", "TWPOST", "KERRYTJ", "PELICAN");
        assertThat(ExpressCompanies.forMarket("TW").getFirst().name()).isEqualTo("黑貓宅急便");
        assertThat(ExpressCompanies.isValidFor("TW", "SF")).isFalse();
    }

    @Test
    @DisplayName("★★★ 大陆市场原样保留 14 家微信码，顺序不变")
    void cn_market_keeps_the_14_wechat_codes_in_order() {
        assertThat(ExpressCompanies.forMarket("CN")).extracting(ExpressCompanies.Company::code)
                .containsExactly("SF", "ZTO", "YTO", "YD", "STO", "JTSD", "JD", "YZPY", "EMS", "DBL", "HTKY",
                        "FWX", "UC", "ZJS");
        assertThat(ExpressCompanies.isValidFor("CN", "TCAT")).isFalse();
    }

    @Test
    @DisplayName("★★ 不认得的市场给空表，不回落大陆")
    void unknown_market_is_empty() {
        assertThat(ExpressCompanies.forMarket("AE")).isEmpty();
        assertThat(ExpressCompanies.forMarket(null)).isEmpty();
        assertThat(ExpressCompanies.isValidFor("AE", "SF")).isFalse();
    }

    @Test
    @DisplayName("★★★ resolve：码原样返回；本市场名称（去首尾空白）归一成码；别的市场的名称不归一")
    void resolve_normalizes_names_within_market_only() {
        assertThat(ExpressCompanies.resolve("TW", "TCAT")).contains("TCAT");
        assertThat(ExpressCompanies.resolve("TW", " 黑貓宅急便 ")).contains("TCAT");
        assertThat(ExpressCompanies.resolve("CN", "顺丰速运")).contains("SF");
        assertThat(ExpressCompanies.resolve("TW", "顺丰速运")).isEqualTo(Optional.empty());
        assertThat(ExpressCompanies.resolve("TW", "SF")).isEmpty();
        assertThat(ExpressCompanies.resolve("TW", null)).isEmpty();
        assertThat(ExpressCompanies.resolve("TW", "  ")).isEmpty();
    }

    @Test
    @DisplayName("码全局唯一：nameOf 不带市场也能反查")
    void name_of_is_cross_market() {
        assertThat(ExpressCompanies.nameOf("TCAT")).isEqualTo("黑貓宅急便");
        assertThat(ExpressCompanies.nameOf("SF")).isEqualTo("顺丰速运");
        assertThat(ExpressCompanies.nameOf("NOPE")).isNull();
    }
}
