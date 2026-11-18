package ai.neargo.shop.gateway.core.resource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 被停用的链接的本地快照（TDD-网关层与统一权限 §2.13）：逐请求只查这份快照，不查库。
 *
 * <p>60 秒过期后重读。读库失败时沿用手上的那一份并告警 —— 权限库短暂不可用不该让整端的请求失败，
 * 代价是这段时间里新停用的链接还没生效。与角色权限的快照（{@code RolePermResolver}）是同一种做法。
 */
@Component
public class ResourceStatusCache {

    private static final Logger log = LoggerFactory.getLogger(ResourceStatusCache.class);
    private static final long TTL_MS = 60_000;

    private record Snapshot(Set<String> disabled, long loadedAt) {
    }

    private final ObjectProvider<ResourceStore> store;
    private final Map<String, Snapshot> byEnd = new ConcurrentHashMap<>();

    public ResourceStatusCache(ObjectProvider<ResourceStore> store) {
        this.store = store;
    }

    public boolean isDisabled(String endCode, ApiResource resource) {
        return snapshot(endCode).disabled().contains(resource.method() + " " + resource.pattern());
    }

    /** 本实例上改了停用状态之后调它，下一个请求就读到新值；其他实例等快照过期。 */
    public void invalidate(String endCode) {
        byEnd.remove(endCode);
    }

    private Snapshot snapshot(String endCode) {
        Snapshot current = byEnd.get(endCode);
        long now = System.currentTimeMillis();
        if (current != null && now - current.loadedAt() < TTL_MS) {
            return current;
        }
        ResourceStore source = store.getIfAvailable();
        if (source == null) {
            return new Snapshot(Set.of(), now);
        }
        try {
            Snapshot fresh = new Snapshot(Set.copyOf(source.disabledKeys(endCode)), now);
            byEnd.put(endCode, fresh);
            return fresh;
        } catch (RuntimeException e) {
            log.warn("读接口资源的停用状态失败，沿用上一份快照 end={} 异常={}", endCode, e.getClass().getSimpleName());
            Snapshot kept = current == null ? new Snapshot(Set.of(), now) : new Snapshot(current.disabled(), now);
            byEnd.put(endCode, kept);
            return kept;
        }
    }
}
