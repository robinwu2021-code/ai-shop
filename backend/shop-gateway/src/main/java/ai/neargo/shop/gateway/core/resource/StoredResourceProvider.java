package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 库里录入的微服务链接（TDD-网关层与统一权限 §2.18）。端码取地址前缀去掉开头的斜杠：{@code /ops} → {@code ops}。
 * 读库失败时这一轮当作没有录入的链接，并告警 —— 本进程入口的判权不受影响。
 */
@Component
public class StoredResourceProvider implements ResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(StoredResourceProvider.class);

    private final ObjectProvider<ResourceStore> store;
    private final Environment environment;

    public StoredResourceProvider(ObjectProvider<ResourceStore> store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    /** 这一端打开了转发才把录入的链接放进索引；没开时它们只是库里的几行，不参与定位与判权。 */
    private boolean proxies(String prefix) {
        String end = "/pay-ops".equals(prefix) ? "ops" : prefix.substring(1);
        return environment.getProperty("shop.gateway." + end + ".proxy", Boolean.class, false);
    }

    @Override
    public List<ApiResource> load(List<String> pathPrefixes) {
        List<ApiResource> out = new ArrayList<>();
        ResourceStore source = store.getIfAvailable();
        if (source == null) {
            return out;
        }
        for (String prefix : pathPrefixes) {
            if (!proxies(prefix)) {
                continue;
            }
            try {
                for (ApiResource resource : source.loadManual(prefix.substring(1))) {
                    // 录入的地址必须落在这一端的前缀下，否则不认
                    if (resource.pattern().startsWith(prefix + "/")) {
                        out.add(resource);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("读录入的微服务链接失败，本轮按没有处理 prefix={} 异常={}", prefix, e.getClass().getSimpleName());
            }
        }
        return out;
    }
}
