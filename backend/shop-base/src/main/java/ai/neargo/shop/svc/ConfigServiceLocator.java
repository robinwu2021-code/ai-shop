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

    /**
     * 服务名 → 基址。
     *
     * <p><b>查找大小写不敏感</b>，这不是宽容，是必须的：
     * 环境变量 {@code SHOP_SERVICES_TARGETS_PAY} 经 Spring 的 relaxed binding
     * 进到 Map 里，键是 <b>小写的 {@code pay}</b>，而调用方按
     * {@link ServiceName#PAY}（大写）查 —— 对不上。
     *
     * <p>2026-09-01 生产上就是这么失败的：本地测试用的是命令行参数
     * {@code --shop.services.targets.PAY}（保留大小写）所以一路绿灯，
     * 换成环境变量的生产环境第一次调用就 NOT_CONFIGURED。
     * <b>本地与生产的配置注入方式不同，而这个差异只在 Map 类型上暴露</b>。
     */
    private final Map<String, String> targets = new LinkedHashMap<>();

    @Override
    public Optional<String> baseUrlOf(String service) {
        String raw = targets.get(service);
        if (raw == null) {
            // 大小写不敏感地再找一遍 —— 环境变量注入的键是小写的
            raw = targets.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(service))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
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
