package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.api.guard.PortalFace;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按入口给出管线过滤器（ADR-044 / ADR-045）：平台的安全配置为每个入口建一条链，链上挂这里给的过滤器。
 * 每个入口恰好一个 {@link EntryGuard}；本进程没装的入口没有过滤器（{@code shop.pay.guard.entries}），要了即失败。
 */
@Component
public class GuardFilters {

    private final Map<PortalFace, EntryGuard> byEntry = new EnumMap<>(PortalFace.class);
    private final RequestLogWriter log;

    public GuardFilters(List<EntryGuard> guards, RequestLogWriter log) {
        for (EntryGuard g : guards) {
            if (byEntry.put(g.entry(), g) != null) {
                throw new IllegalStateException("入口 " + g.entry() + " 有两个守卫");
            }
        }
        this.log = log;
    }

    public GuardFilter forEntry(PortalFace entry) {
        EntryGuard g = byEntry.get(entry);
        if (g == null) {
            throw new IllegalStateException("入口 " + entry + " 在本进程没有守卫（shop.pay.guard.entries）");
        }
        return new GuardFilter(entry, g, log);
    }
}
