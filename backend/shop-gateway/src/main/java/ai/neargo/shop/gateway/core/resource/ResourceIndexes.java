package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final List<ResourceProvider> providers;
    private final Map<String, Built> byPrefixes = new ConcurrentHashMap<>();

    public ResourceIndexes(List<ResourceProvider> providers) {
        this.providers = providers;
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
        Built fresh = new Built(new ResourceIndex(resources), now);
        byPrefixes.put(key, fresh);
        return fresh.index();
    }

    /** 下一次取索引时重建。 */
    public void invalidate() {
        byPrefixes.clear();
    }
}
