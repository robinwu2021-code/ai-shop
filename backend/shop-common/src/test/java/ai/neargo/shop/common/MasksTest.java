package ai.neargo.shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏口径的单元测试（U3）。
 *
 * <p>它值得单独测的原因不是逻辑复杂，而是**这段代码错了不会报错** ——
 * 多留几位、少留几位，看到的人都不会觉得异常，直到有人拿它去比对另一处。
 */
class MasksTest {

    @ParameterizedTest
    @CsvSource({
            "13800138000, 138****8000",
            "  13800138000  , 138****8000",   // 前后空白不该影响结果
    })
    @DisplayName("手机号留头三尾四 —— 本人要能认出是不是自己的号")
    void phoneKeepsHeadAndTail(String raw, String expected) {
        assertThat(Masks.phone(raw)).isEqualTo(expected);
    }

    /**
     * 位数不够时**退化而不是抛错**：脱敏是展示逻辑，
     * 为一条脏数据让整个页面 500 是不成比例的。
     */
    @ParameterizedTest
    @ValueSource(strings = {"1380013", "138"})
    @DisplayName("位数不足时退化成只留尾四，不抛错")
    void shortPhoneDegrades(String raw) {
        assertThat(Masks.phone(raw)).startsWith("****");
    }

    @ParameterizedTest
    @CsvSource({
            "6222021234567890123, ****0123",
            "SUB1720368602,       ****8602",
            "12345,               ****2345",
    })
    @DisplayName("★ 账号类只留尾四 —— 银行卡与二级商户号必须同一口径")
    void tailKeepsLastFour(String raw, String expected) {
        assertThat(Masks.tail(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 四位及以下全掩 —— 留一位都算泄露")
    void shortAccountFullyMasked() {
        assertThat(Masks.tail("1234")).isEqualTo("****");
        assertThat(Masks.tail("12")).isEqualTo("****");
    }

    /**
     * 空值返回 null 而不是空串：前端拿到 null 才知道「没有这个值」，
     * 拿到空串会渲染成一个空字段，看着像数据丢了。
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("空值返回 null，不返回空串")
    void blankReturnsNull(String raw) {
        assertThat(Masks.phone(raw)).isNull();
        assertThat(Masks.tail(raw)).isNull();
    }

    @Test
    @DisplayName("★ 脱敏结果里不能残留原值的可识别片段")
    void maskedValueLeaksNothingBeyondTail() {
        String account = "6222021234567890123";
        String masked = Masks.tail(account);
        // 掩码之外只应剩尾四位；把尾四位去掉后，不该还含有原号的任何片段
        assertThat(masked.replace("****", "")).isEqualTo("0123");
        assertThat(masked).doesNotContain("622202");
    }
}
