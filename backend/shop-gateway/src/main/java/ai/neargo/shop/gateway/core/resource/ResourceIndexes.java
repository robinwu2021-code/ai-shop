package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 各端的资源索引，按地址前缀各建一份，第一次用到时建，之后每 60 秒重建一次。
 *
 * <p>不在启动时建：过滤器链先于 Spring MVC 的处理方法登记完成，那时取到的入口是空的。
 * 定时重建是为了库里录入的微服务链接：运营调整之后最长 60 秒生效，本实例上可以用 {@link #invalidate()} 立即生效。
 * 本进程入口每次重建扫出来的都一样，几百条，重建的代价可以忽略。
 */
@Component
public class ResourceIndexes {

    private static final long TTL_MS = 60_000;

    private record Built(ResourceIndex index, long at) {
    }

    private static final Logger log = LoggerFactory.getLogger(ResourceIndexes.class);

    private final List<ResourceProvider> providers;
    private final Map<String, Built> byPrefixes = new ConcurrentHashMap<>();
    private ObjectProvider<ResourceStore> store;
    private Environment environment;

    public ResourceIndexes(List<ResourceProvider> providers) {
        this.providers = providers;
    }

    /** 接口资源表与配置；没有（裁剪部署、单测）时规则就是入口注解上的那一份。 */
    @Autowired
    void setRuleSource(ObjectProvider<ResourceStore> store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    /**
     * 本进程里实际登记的入口，规则取自入口注解，<b>不叠加库里的调整</b>。
     * 只给启动同步用：它要把「注解上写的」抄进库，拿叠加过的去抄会把调整写回成注解的值。
     */
    public List<ApiResource> scanned(List<String> pathPrefixes) {
        List<ApiResource> resources = new ArrayList<>();
        providers.forEach(provider -> resources.addAll(provider.load(pathPrefixes)));
        return new ResourceIndex(resources).all();
    }

    /**
     * 规则以库里为准（{@code shop.gateway.rules-source=db}，默认）：入口注解只在启动时抄进库一次，
     * 之后网关按库里的生效规则判；业务代码不为网关改写法，要调整就在库里调。
     * 入口有哪些仍以本进程实际登记的为准 —— 库里有、本进程没有的入口无处可去。
     * 库里还没有的入口（第一次启动、同步之前）与读库失败时，用入口注解上的那一份。
     */
    private List<ApiResource> effective(List<String> pathPrefixes, List<ApiResource> resources) {
        ResourceStore source = store == null ? null : store.getIfAvailable();
        if (source == null || environment == null
                || !"db".equalsIgnoreCase(environment.getProperty("shop.gateway.rules-source", "db"))) {
            return resources;
        }
        Map<String, ApiResource> stored = new java.util.HashMap<>();
        try {
            for (String prefix : pathPrefixes) {
                for (ApiResource row : source.loadEffective(prefix.substring(1))) {
                    stored.put(row.method() + " " + row.pattern(), row);
                }
            }
        } catch (RuntimeException e) {
            log.warn("读接口资源的生效规则失败，本轮按入口注解判 prefixes={} 异常={}", pathPrefixes,
                    e.getClass().getSimpleName());
            return resources;
        }
        // 先按「方法 + 地址」把本进程的入口并好（同一地址多个处理方法的并法在 ResourceIndex 里），再逐条换成库里的
        List<ApiResource> out = new ArrayList<>();
        for (ApiResource resource : new ResourceIndex(resources).all()) {
            ApiResource row = resource.remote() ? null : stored.get(resource.method() + " " + resource.pattern());
            out.add(row == null ? resource : row);
        }
        return out;
    }

    public ResourceIndex of(List<String> pathPrefixes) {
        String key = String.join(",", pathPrefixes);
        Built current = byPrefixes.get(key);
        long now = System.currentTimeMillis();
        if (current != null && now - current.at() < TTL_MS) {
            return current.index();
        }
        List<ApiResource> resources = new ArrayList<>();
        providers.forEach(provider -> resources.addAll(provider.load(pathPrefixes)));
        Built fresh = new Built(new ResourceIndex(effective(pathPrefixes, resources)), now);
        byPrefixes.put(key, fresh);
        return fresh.index();
    }

    /** 下一次取索引时重建。 */
    public void invalidate() {
        byPrefixes.clear();
    }
}
