package ai.neargo.shop.gateway.core.end;

import java.util.ArrayList;
import java.util.List;

/**
 * 三端的会话有没有落在同一处（ADR-061 第 2 条）。
 *
 * <p>只比「用同一种存储的两端」：一端用库、一端用 Redis，本来就不在一处。
 * 没配自己那一份的端记作落在共用的那一份上 —— 两端都没配，就是同一处。
 */
public final class GatewayIsolationCheck {

    /**
     * @param kind   db / redis / 其他（内存、Ehcache 都是进程内的，不比）
     * @param target 该端这种存储实际指向哪：自己那一份的地址，没配则为 null（= 共用的那一份）
     */
    public record EndTarget(String end, String kind, String target) {
    }

    private GatewayIsolationCheck() {
    }

    /** 返回每一处重合的说明；空 = 三端互不相干。 */
    public static List<String> overlaps(List<EndTarget> ends) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                EndTarget a = ends.get(i);
                EndTarget b = ends.get(j);
                boolean comparable = a.kind().equals(b.kind())
                        && (SessionStoreKinds.DB.equals(a.kind()) || SessionStoreKinds.REDIS.equals(a.kind()));
                if (!comparable) {
                    continue;
                }
                String where = a.target() == null ? "共用的那一份" : a.target();
                if (java.util.Objects.equals(a.target(), b.target())) {
                    out.add("%s 与 %s 的会话都用 %s，且都指向%s".formatted(a.end(), b.end(), a.kind(), where));
                }
            }
        }
        return out;
    }
}
