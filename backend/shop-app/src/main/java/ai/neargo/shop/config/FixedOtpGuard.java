package ai.neargo.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

/**
 * 预设验证码（{@code shop.auth.otp.fixed}）的**最后一道闸**：prod 下直接拒绝启动。
 *
 * <p>那个配置是测试环境的必需品 —— 自动化 e2e 收不到真短信，也不该为了跑测试
 * 去接一条真实短信通道。但它同时是一把**能登进任何人账号的万能钥匙**：
 * 知道这个码 + 知道手机号 = 拿到那个人的会话。
 *
 * <p>危险的从来不是「有人故意在生产开它」，而是**配置继承**：
 * 测试环境的 env 文件被拷成生产的模板、CI 的变量组被复用、
 * 或者干脆是 `SHOP_OTP_FIXED` 留在了某台机器的 shell profile 里。
 * 这类事故没有任何症状 —— 系统工作得完全正常，只是所有人的账号都是公开的。
 *
 * <p>所以这里不是「警告」而是**拒绝启动**：起不来会有人立刻处理，
 * 而一条启动日志里的红字，没有人会在半年后回头看。
 *
 * <p>与 {@code DevSeeder} 同一手法（默认关 + 显式开关），区别是这条还带了
 * 环境判据：种子数据灌错了可以删，会话被别人拿走删不掉。
 */
@Configuration
public class FixedOtpGuard {

    /** 认这几个 profile 为「生产」。命名随部署走，多写几个别名比漏一个安全 */
    private static final String[] PROD_PROFILES = {"prod", "production", "prd"};

    private final String fixedOtp;
    private final Environment env;

    public FixedOtpGuard(@Value("${shop.auth.otp.fixed:}") String fixedOtp, Environment env) {
        this.fixedOtp = fixedOtp;
        this.env = env;
    }

    @PostConstruct
    void check() {
        if (fixedOtp == null || fixedOtp.isBlank()) {
            return;
        }
        boolean prod = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> Arrays.stream(PROD_PROFILES).anyMatch(x -> x.equalsIgnoreCase(p)));
        if (prod) {
            throw new IllegalStateException(
                    "shop.auth.otp.fixed 在生产 profile 下被设置了（值：" + fixedOtp + "）。"
                            + "它让任何人都能用这个码登录任意手机号 —— 拒绝启动。"
                            + "清掉 SHOP_OTP_FIXED 这个环境变量再起。");
        }
    }
}
