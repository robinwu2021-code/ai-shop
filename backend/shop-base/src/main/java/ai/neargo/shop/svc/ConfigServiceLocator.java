package ai.neargo.shop.svc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从配置读服务地址 —— <b>今天唯一的实现</b>。
 *
 * <pre>
 * shop:
 *   services:
 *     targets:
 *       PLATFORM: http://127.0.0.1:8081
 *       PAY:      http://127.0.0.1:8083
 * </pre>
 *
 * <p>延续 {@code shop-job} 已有的 {@code targets} 写法（它跑了一段时间，
 * 形状被验证过），只是把它从 worker 里提上来，让三个进程共用一份。
 *
 * <p><b>多机时把值里的 IP 换成域名即可</b>，这个类不需要改：
 * 它读到的只是一个 URL，是 IP 还是主机名对它没有区别。
 * 所以「引入 DNS 服务发现」在这套结构下是一次运维动作，不是一次发版。
 */
@Component
@ConfigurationProperties(prefix = "shop.services")
public class ConfigServiceLocator implements ServiceLocator {

    /** 服务名 → 基址。**键区分大小写**，用 {@link ServiceName} 里的常量 */
    private final Map<String, String> targets = new LinkedHashMap<>();

    @Override
    public Optional<String> baseUrlOf(String service) {
        String raw = targets.get(service);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // 尾斜杠在这里统一去掉：调用方拼路径时一律以 / 开头，
        // 两边都留斜杠会拼出 //internal —— 那在有些反代下会 404，而且很难看出来
        return Optional.of(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
    }

    public Map<String, String> getTargets() {
        return targets;
    }
}
