package ai.neargo.shop.user.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TDD-注册地区与三步建店 AC3：注册时自报的地区只在可注册清单内才算数，其余一律落部署默认。 */
class RegistrationMarketsTest {

    @Test
    @DisplayName("AC3 清单内的地区照用（大小写、空白不敏感）；清单外 / 空一律落部署默认")
    void onlyListedMarketsAreAccepted() {
        var markets = RegistrationMarkets.of(" tw, CN ,AE ", "tw");
        assertThat(markets.resolve("CN")).isEqualTo("CN");
        assertThat(markets.resolve(" ae ")).isEqualTo("AE");
        assertThat(markets.resolve("JP")).isEqualTo("TW");
        assertThat(markets.resolve("")).isEqualTo("TW");
        assertThat(markets.resolve(null)).isEqualTo("TW");
        assertThat(markets.defaultMarket()).isEqualTo("TW");
    }

    @Test
    @DisplayName("AC3 未配置可注册地区 = 只有部署默认：自报别的地区不生效")
    void unconfiguredListMeansOnlyTheDeploymentDefault() {
        var markets = RegistrationMarkets.of("", "CN");
        assertThat(markets.resolve("TW")).isEqualTo("CN");
        assertThat(markets.resolve("CN")).isEqualTo("CN");
        assertThat(RegistrationMarkets.of(null, null).resolve("TW")).isEqualTo("CN");
    }
}
