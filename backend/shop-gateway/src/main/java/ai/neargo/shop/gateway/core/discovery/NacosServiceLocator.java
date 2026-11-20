package ai.neargo.shop.gateway.core.discovery;

import ai.neargo.shop.svc.ServiceLocator;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 先问 Nacos、再回落到配置的服务定位（ADR-062、TDD-网关层与统一权限 §2.24）。
 *
 * <p>调用方仍然只给服务名，这一层替它决定地址从哪来：
 * <ol>
 *   <li>这个服务没有在 {@code shop.nacos.discovery.services} 里登记 → 直接读配置，与今天完全一样；</li>
 *   <li>登记了 → 向 Nacos 要一个健康的实例；</li>
 *   <li>Nacos 没有健康实例，或者 Nacos 本身不可用 → 回落到配置里的地址。</li>
 * </ol>
 *
 * <p>第 3 条是有意的：注册中心是新加的一环，它出问题不该让原本能调通的服务调不通。
 * 回落时记一条告警（每个服务只在状态变化时记，不刷屏）。
 */
public class NacosServiceLocator implements ServiceLocator {

    private static final Logger log = LoggerFactory.getLogger(NacosServiceLocator.class);

    private final NamingService naming;
    private final ServiceLocator configured;
    private final Map<String, String> services;
    private final String group;
    /** 每个服务上一次是从哪来的：只在变化时记日志。 */
    private final Map<String, String> lastSource = new ConcurrentHashMap<>();

    public NacosServiceLocator(NamingService naming, ServiceLocator configured, Map<String, String> services,
                               String group) {
        this.naming = naming;
        this.configured = configured;
        this.services = services;
        this.group = group;
    }

    @Override
    public Optional<String> baseUrlOf(String service) {
        String nacosName = nacosNameOf(service);
        if (nacosName == null) {
            return configured.baseUrlOf(service);
        }
        try {
            Instance instance = naming.selectOneHealthyInstance(nacosName, group);
            if (instance != null) {
                String scheme = instance.getMetadata() == null ? null : instance.getMetadata().get("scheme");
                String url = (scheme == null || scheme.isBlank() ? "http" : scheme) + "://" + instance.getIp() + ":"
                        + instance.getPort();
                note(service, "nacos", "服务 {} 的地址取自 Nacos（{}）", service, nacosName);
                return Optional.of(url);
            }
            note(service, "config", "Nacos 里服务 {}（{}）没有健康实例，回落到配置的地址", service, nacosName);
        } catch (NacosException | RuntimeException e) {
            // 没有健康实例时客户端抛的也是异常，与「Nacos 不可用」一样处理：回落
            note(service, "config", "从 Nacos 取服务 {}（{}）失败，回落到配置的地址：" + e.getMessage(), service, nacosName);
        }
        return configured.baseUrlOf(service);
    }

    /** 与配置的查找一样大小写不敏感：环境变量注入的键是小写的。 */
    private String nacosNameOf(String service) {
        String direct = services.get(service);
        if (direct != null) {
            return direct.isBlank() ? null : direct;
        }
        return services.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(service))
                .map(Map.Entry::getValue).filter(v -> !v.isBlank()).findFirst().orElse(null);
    }

    private void note(String service, String source, String message, Object... args) {
        if (!source.equals(lastSource.put(service, source))) {
            if ("nacos".equals(source)) {
                log.info(message, args);
            } else {
                log.warn(message, args);
            }
        }
    }
}
