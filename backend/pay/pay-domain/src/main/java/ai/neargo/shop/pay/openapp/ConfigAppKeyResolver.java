package ai.neargo.shop.pay.openapp;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ⏸ 过渡实现（安全规范 §3.1）：从部署配置读试点接入方的 HMAC 密钥 ——
 * {@code shop.pay.open.apps.{appNo}.hmac-secret}，轮换期另配 {@code .hmac-secret-next}。<b>没有默认值</b>。
 * 密钥模块（{@code secret}）就绪后换成从 {@code sec_key} 取的实现。
 */
@Component
public class ConfigAppKeyResolver implements AppKeyResolver {

    private final Environment env;

    public ConfigAppKeyResolver(Environment env) {
        this.env = env;
    }

    @Override
    public List<String> hmacSecretsOf(String appNo) {
        List<String> keys = new ArrayList<>();
        for (String suffix : List.of("hmac-secret", "hmac-secret-next")) {
            String k = env.getProperty("shop.pay.open.apps." + appNo + "." + suffix);
            if (k != null && !k.isBlank()) {
                keys.add(k);
            }
        }
        return keys;
    }
}
