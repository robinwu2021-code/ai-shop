package ai.neargo.shop.trade.order.machine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 测试用：把文档迁移表的一行（多出发 × 多执行者）展开成 Invariants.edges 的字符串形状。 */
public final class EdgeTable {

    private final Set<String> edges = new LinkedHashSet<>();

    public EdgeTable row(List<String> froms, String event, List<String> actors, String... targets) {
        String to = List.of(targets).stream().sorted().toList().toString();
        for (String f : froms) {
            for (String a : actors) {
                edges.add(f + "|" + event + "|" + a + "→" + to);
            }
        }
        return this;
    }

    public EdgeTable stay(List<String> froms, String event, List<String> actors) {
        for (String f : froms) {
            for (String a : actors) {
                edges.add(f + "|" + event + "|" + a + "→[" + f + "]");
            }
        }
        return this;
    }

    public Set<String> edges() {
        return edges;
    }
}
