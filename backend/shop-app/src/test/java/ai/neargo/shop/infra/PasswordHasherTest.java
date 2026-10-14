package ai.neargo.shop.infra;

import ai.neargo.shop.auth.PasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希（纯计算，不起 Spring）。
 *
 * <p>守的是「换 bcrypt」这件事真正的风险点：**双格式共存期间不能把人锁在门外**，
 * 也不能把错误密码写进库。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    /** 一期占位哈希，用来造存量数据。与被替换掉的实现逐字相同。 */
    private static String legacy(String raw) {
        return Integer.toHexString(("shop$" + raw).hashCode());
    }

    @Test
    @DisplayName("★★★ 同一明文两次编码结果不同 —— 这条直接证伪旧实现")
    void encodeIsSalted() {
        String a = hasher.encode("admin123");
        String b = hasher.encode("admin123");
        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.matches("admin123", a)).isTrue();
        assertThat(hasher.matches("admin123", b)).isTrue();
        // 旧实现同明文必然同哈希 —— 一张彩虹表通吃全部账号
        assertThat(legacy("admin123")).isEqualTo(legacy("admin123"));
    }

    @Test
    @DisplayName("★★★ 存量（旧格式）密码仍能验过 —— 否则升级那天全员被锁在门外")
    void legacyStillVerifies() {
        String stored = legacy("admin123");
        assertThat(hasher.matches("admin123", stored)).isTrue();
        assertThat(hasher.matches("wrong", stored)).isFalse();
    }

    @Test
    @DisplayName("★★★ needsUpgrade 只对旧格式为真 —— 它决定要不要重写库")
    void needsUpgradeDetectsFormat() {
        assertThat(hasher.needsUpgrade(legacy("x"))).isTrue();
        assertThat(hasher.needsUpgrade(hasher.encode("x"))).isFalse();
        // 空值不该被当成「要升级」：那会在数据异常时触发一次莫名其妙的写库
        assertThat(hasher.needsUpgrade(null)).isFalse();
        assertThat(hasher.needsUpgrade("")).isFalse();
    }

    @Test
    @DisplayName("★★ bcrypt 串能被认出来 —— 前缀判定要认 $2a/$2b/$2y")
    void recognisesAllBcryptVariants() {
        for (String p : new String[]{"$2a$", "$2b$", "$2y$"}) {
            assertThat(hasher.needsUpgrade(p + "10$" + "x".repeat(53)))
                    .as("%s 是 bcrypt，不该被判成待升级", p).isFalse();
        }
    }

    @Test
    @DisplayName("★★ 空 stored 一律不通过 —— 数据异常时不能因为「两边都空」而放行")
    void blankStoredNeverMatches() {
        assertThat(hasher.matches("", null)).isFalse();
        assertThat(hasher.matches("", "")).isFalse();
        assertThat(hasher.matches("anything", "   ")).isFalse();
    }
}
