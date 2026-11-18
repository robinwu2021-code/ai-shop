package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 各端的资源索引，按地址前缀各建一份，第一次用到时建。
 *
 * <p>不在启动时建：过滤器链先于 Spring MVC 的处理方法登记完成，那时取到的入口是空的。
 * 第一次请求到来时入口已全部登记，建一次之后不再变。
 */
@Component
public class ResourceIndexes {

    private final List<ResourceProvider> providers;
    private final Map<String, ResourceIndex> byPrefixes = new ConcurrentHashMap<>();

    public ResourceIndexes(List<ResourceProvider> providers) {
        this.providers = providers;
    }

    public ResourceIndex of(List<String> pathPrefixes) {
        return byPrefixes.computeIfAbsent(String.join(",", pathPrefixes), key -> {
            List<ApiResource> resources = new ArrayList<>();
            providers.forEach(provider -> resources.addAll(provider.load(pathPrefixes)));
            return new ResourceIndex(resources);
        });
    }
}
