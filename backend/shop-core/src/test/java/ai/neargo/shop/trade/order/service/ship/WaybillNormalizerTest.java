package ai.neargo.shop.trade.order.service.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaybillNormalizerTest {

    @Test
    @DisplayName("去首尾与中间空白")
    void stripsWhitespace() {
        assertThat(WaybillNormalizer.normalize("  773445089430765 ")).isEqualTo("773445089430765");
        assertThat(WaybillNormalizer.normalize("SF 1234 5678")).isEqualTo("SF12345678");
        assertThat(WaybillNormalizer.normalize("SF123\n")).isEqualTo("SF123");
    }

    @Test
    @DisplayName("去中文前缀与冒号")
    void stripsChinesePrefixAndColon() {
        assertThat(WaybillNormalizer.normalize("单号：SF123")).isEqualTo("SF123");
        assertThat(WaybillNormalizer.normalize("快递单号 SF123")).isEqualTo("SF123");
    }

    @Test
    @DisplayName("全角转半角 + 大写")
    void fullWidthAndUppercase() {
        assertThat(WaybillNormalizer.normalize("７７３４４５")).isEqualTo("773445");
        assertThat(WaybillNormalizer.normalize("sf123")).isEqualTo("SF123");
    }

    @Test
    @DisplayName("保留连字符（部分承运商单号含 -）")
    void keepsHyphen() {
        assertThat(WaybillNormalizer.normalize("abc-123")).isEqualTo("ABC-123");
    }

    @Test
    @DisplayName("null / 空 安全")
    void nullSafe() {
        assertThat(WaybillNormalizer.normalize(null)).isNull();
        assertThat(WaybillNormalizer.normalize("   ")).isEmpty();
    }
}
