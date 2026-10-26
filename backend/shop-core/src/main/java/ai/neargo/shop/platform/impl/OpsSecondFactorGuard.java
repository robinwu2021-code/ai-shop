package ai.neargo.shop.platform.impl;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 运营登录第二因子的启动检查（支付服务-安全规范 §2.7）：{@code prod} profile 下 {@code shop.ops.auth.second-factor}
 * 只能是 {@code email}，配成 {@code off}（或任何别的值）启动即失败 —— 与 {@code FixedOtpGuard} 拒绝生产用固定验证码同理：
 * 关第二因子只该发生在测试世界里，而漏配一行就静默关掉的开关不能放进生产。
 */
@Component
public class OpsSecondFactorGuard {

    public OpsSecondFactorGuard(Environment env, @Value("${shop.ops.auth.second-factor:email}") String mode) {
        check(Arrays.asList(env.getActiveProfiles()), mode);
    }

    public static void check(List<String> activeProfiles, String mode) {
        if (!"email".equals(mode) && !"off".equals(mode)) {
            throw new IllegalStateException("shop.ops.auth.second-factor 只能是 email / off，实际是 " + mode);
        }
        if (activeProfiles.contains("prod") && !"email".equals(mode)) {
            throw new IllegalStateException("生产（prod profile）不允许关闭运营登录第二因子：shop.ops.auth.second-factor=" + mode);
        }
    }
}
