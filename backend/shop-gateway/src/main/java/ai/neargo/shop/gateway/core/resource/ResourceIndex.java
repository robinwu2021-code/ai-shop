package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 一端的资源索引：按「方法 + 地址」定位一条链接。建好之后不变。
 *
 * <p>不带路径变量的地址放哈希表直接命中；带变量的逐条匹配，取最具体的那条
 * （与 Spring MVC 选处理方法用的是同一套路径模式与同一个比较规则）。几百条的量级，不需要更复杂的结构。
 */
public final class ResourceIndex {

    private record Entry(PathPattern pattern, ApiResource resource) {
    }

    private final Map<String, ApiResource> exact = new HashMap<>();
    private final List<Entry> templated = new ArrayList<>();
    private final List<ApiResource> all;

    public ResourceIndex(List<ApiResource> resources) {
        // 同一个「方法 + 地址」可以登记多个处理方法（靠请求参数或请求头再区分）。提前判权只看方法与地址，
        // 分不清会落到哪一个：权限要求相同就并成一行；不同就标成「留给方法判」，不猜。
        Map<String, ApiResource> merged = new java.util.LinkedHashMap<>();
        for (ApiResource resource : resources) {
            merged.merge(resource.method() + " " + resource.pattern(), resource, (first, second) ->
                    first.access() == second.access() && java.util.Objects.equals(first.rule(), second.rule())
                            ? first
                            : new ApiResource(first.method(), first.pattern(), first.location(),
                                    ai.neargo.shop.gateway.core.end.Access.DEFER, null,
                                    first.handler() + " | " + second.handler()));
        }
        this.all = List.copyOf(merged.values());
        PathPatternParser parser = new PathPatternParser();
        for (ApiResource resource : all) {
            if (resource.pattern().contains("{") || resource.pattern().contains("*")) {
                templated.add(new Entry(parser.parse(resource.pattern()), resource));
            } else {
                exact.put(resource.method() + " " + resource.pattern(), resource);
            }
        }
        templated.sort(Comparator.comparing(Entry::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    }

    public Optional<ApiResource> locate(String method, String path) {
        ApiResource hit = exact.get(method + " " + path);
        if (hit == null) {
            hit = exact.get("* " + path);
        }
        if (hit != null) {
            return Optional.of(hit);
        }
        PathContainer container = PathContainer.parsePath(path);
        for (Entry entry : templated) {
            boolean verb = entry.resource().method().equals(method) || entry.resource().method().equals("*");
            if (verb && entry.pattern().matches(container)) {
                return Optional.of(entry.resource());
            }
        }
        return Optional.empty();
    }

    public List<ApiResource> all() {
        return all;
    }
}
