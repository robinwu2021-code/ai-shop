package ai.neargo.shop.trade.order.service.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaybillFormatTest {

    @Test
    @DisplayName("正常单号通过")
    void valid() {
        assertThat(WaybillFormat.isValid("773445089430765")).isTrue();
        assertThat(WaybillFormat.isValid("SF1234567890")).isTrue();
        assertThat(WaybillFormat.isValid("ABC-123456")).isTrue();
    }

    @Test
    @DisplayName("太短/太长拒")
    void lengthBounds() {
        assertThat(WaybillFormat.isValid("123")).isFalse();
        assertThat(WaybillFormat.isValid("1".repeat(33))).isFalse();
    }

    @Test
    @DisplayName("含非法字符/空/null 拒（normalize 后仍残留则格式不合法）")
    void illegalChars() {
        assertThat(WaybillFormat.isValid("SF 123456")).isFalse();   // 含空格（未清洗或清洗漏）
        assertThat(WaybillFormat.isValid("中文123456")).isFalse();
        assertThat(WaybillFormat.isValid("")).isFalse();
        assertThat(WaybillFormat.isValid(null)).isFalse();
    }
}
