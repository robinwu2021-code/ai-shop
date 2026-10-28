package ai.neargo.shop.pay.openapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 查询只回掩码（开发计划 M3.5；API 清单 §2.2：{@code 8123****4567}） */
class SensitiveMasksTest {

    @Test
    @DisplayName("★★★ 号码类：留前 4 后 4；短的留首尾各 1；更短的全遮")
    void numbers() {
        assertThat(SensitiveMasks.mask("accountNo", "8123004567")).isEqualTo("8123****4567");
        assertThat(SensitiveMasks.mask("phone", "13800005678")).isEqualTo("1380****5678");
        assertThat(SensitiveMasks.mask("bankCode", "012")).isEqualTo("0*2");
        assertThat(SensitiveMasks.mask("bankCode", "01")).isEqualTo("**");
    }

    @Test
    @DisplayName("★★ 邮箱留首字与域名；姓名留首字；空值不出现")
    void emailAndName() {
        assertThat(SensitiveMasks.mask("email", "alice@example.com")).isEqualTo("a****@example.com");
        assertThat(SensitiveMasks.mask("name", "王小明")).isEqualTo("王**");
        assertThat(SensitiveMasks.mask("accountName", "Lin")).isEqualTo("L**");
        assertThat(SensitiveMasks.mask("phone", null)).isNull();
    }
}
