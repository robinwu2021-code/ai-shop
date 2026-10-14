package ai.neargo.shop.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 预设验证码在生产 profile 下必须**拒绝启动**。
 *
 * <p>为什么值得测：这条闸平时不发生，而它防的那件事**没有任何症状** ——
 * 配置从测试环境被继承到生产（env 文件被拷、CI 变量组被复用、
 * `SHOP_OTP_FIXED` 留在某台机器的 shell profile 里），系统照常工作，
 * 只是所有人的账号都成了公开的。
 *
 * <p>不测的话，下一个人很容易把「拒绝启动」改成「打条日志」——
 * 那正是这道闸失去意义的那一刻。
 */
class FixedOtpGuardTest {

    /** 默认按「短信是桩」跑 —— 那是本地开发的形状 */
    private static void run(String fixed, String... profiles) {
        run(fixed, true, profiles);
    }

    private static void run(String fixed, boolean smsStub, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        new FixedOtpGuard(fixed, smsStub, env).check();
    }

    @Test
    @DisplayName("★★★ prod 下设了预设码 → 拒绝启动（不是警告）")
    void prodRefusesToStart() {
        assertThatThrownBy(() -> run("123456", "api", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动");
    }

    @Test
    @DisplayName("★★ profile 别名也要挡住 —— 命名随部署走，漏一个等于没挡")
    void prodAliasesAlsoRefuse() {
        for (String p : new String[]{"production", "PRD", "Prod"}) {
            assertThatThrownBy(() -> run("000000", p))
                    .as("profile=%s 应当拒绝启动", p)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("★★ 测试环境照常启动 —— 挡得太宽会让人把整道闸删掉")
    void nonProdStartsFine() {
        assertThatCode(() -> run("123456", "api")).doesNotThrowAnyException();
        assertThatCode(() -> run("123456", "api", "h2db")).doesNotThrowAnyException();
    }

    /*
     * ↓ 下面三条是这道闸真正的判据。
     *
     * 上面那条 `run("123456", "api")` 曾经是**唯一**描述「生产」的方式，
     * 而它写的是「照常启动」—— 因为写测试时以为生产叫 prod。
     * 实际生产跑的就是 api,ops：**这个断言当时是绿的，而线上是敞开的**。
     */

    @Test
    @DisplayName("★★★ 短信通道是真的 → 拒绝启动（这才是生产的形状，profile 叫什么都一样）")
    void realSmsChannelRefusesToStart() {
        // 本项目生产实际就是这一组：profile=api,ops + 真短信
        assertThatThrownBy(() -> run("123456", false, "api", "ops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("shop.sms.stub=false");
    }

    @Test
    @DisplayName("★★★ profile 完全不设、只有真短信 → 照样拒绝 —— 不许靠名字兜底")
    void realSmsRefusesEvenWithoutAnyProfile() {
        assertThatThrownBy(() -> run("123456", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★★ 真短信但没设预设码 → 必须能起（生产的正常形态）")
    void realSmsWithoutFixedCodeIsFine() {
        assertThatCode(() -> run("", false, "api", "ops")).doesNotThrowAnyException();
        assertThatCode(() -> run(null, false, "api", "ops")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★★ 没设预设码时，prod 也要能起 —— 默认必须是关的那一侧")
    void blankIsAlwaysFine() {
        assertThatCode(() -> run("", "prod")).doesNotThrowAnyException();
        assertThatCode(() -> run(null, "prod")).doesNotThrowAnyException();
        assertThat(true).isTrue();
    }
}
