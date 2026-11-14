package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretException;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 进程级设置：本进程的环境（{@code shop.secret.env}）与时钟。
 *
 * <p><b>环境没有默认值</b>：漏配时不猜沙箱还是生产，加解密一律拒绝（{@link SecretException.Reason#ENV_NOT_CONFIGURED}）。
 * 启动本身不失败 —— 不用密钥的进程（切片测试、只读报表实例）不该因此起不来。
 */
public record SecretSettings(String env, Clock clock) {

    public SecretSettings {
        if (env != null && !env.isBlank() && !env.equals("SANDBOX") && !env.equals("PRODUCTION")) {
            throw new IllegalArgumentException("shop.secret.env must be SANDBOX or PRODUCTION, got " + env);
        }
        env = env == null || env.isBlank() ? null : env;
    }

    public boolean envConfigured() {
        return env != null;
    }

    String requireEnv() {
        if (env == null) {
            throw new SecretException(SecretException.Reason.ENV_NOT_CONFIGURED,
                    "shop.secret.env is not set; refusing to seal or open secrets");
        }
        return env;
    }

    /** 表上的时间列一律 UTC（DDL 注释） */
    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
