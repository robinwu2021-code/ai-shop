package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One exclusive group sells only its highest-priority menu at any instant; ungrouped menus are
 * unaffected. Equal priority at the same instant is a conflict, never an arbitrary pick.
 */
public final class MenuExclusivePolicy {
    private MenuExclusivePolicy() { }

    /** Half-open interval. */
    public record Window(Instant fromInclusive, Instant toExclusive) { }

    /** A menu with its own (pre-exclusion) sale windows. */
    public record Member<K>(K key, String exclusiveGroup, int priority, List<Window> windows) {
        public Member { windows = List.copyOf(windows); }
    }

    /** Menus already known to be active at one instant: keeps each group's winner, order preserved. */
    public static <T> List<T> winners(List<T> active, Function<T, String> group,
            Function<T, Integer> priority) {
        var best = new LinkedHashMap<String, T>();
        for (var menu : active) {
            var key = group.apply(menu);
            if (key == null) continue;
            var old = best.get(key);
            if (old != null && priority.apply(menu).equals(priority.apply(old))) throw conflict();
            if (old == null || priority.apply(menu) > priority.apply(old)) best.put(key, menu);
        }
        return active.stream().filter(m -> group.apply(m) == null || best.get(group.apply(m)) == m).toList();
    }

    /** Over a horizon: each menu keeps only the time not covered by higher-priority group members. */
    public static <K> Map<K, List<Window>> effective(List<Member<K>> members) {
        var result = new LinkedHashMap<K, List<Window>>();
        for (var member : members) {
            if (member.exclusiveGroup() == null) {
                result.put(member.key(), member.windows());
                continue;
            }
            var higher = new ArrayList<Window>();
            for (var other : members) {
                if (other == member || !member.exclusiveGroup().equals(other.exclusiveGroup())) continue;
                if (other.priority() > member.priority()) higher.addAll(other.windows());
                else if (other.priority() == member.priority() && overlaps(member.windows(), other.windows()))
                    throw conflict();
            }
            result.put(member.key(), subtract(member.windows(), higher));
        }
        return result;
    }

    private static boolean overlaps(List<Window> left, List<Window> right) {
        return left.stream().anyMatch(a -> right.stream().anyMatch(b ->
                a.fromInclusive().isBefore(b.toExclusive()) && b.fromInclusive().isBefore(a.toExclusive())));
    }

    private static List<Window> subtract(List<Window> windows, List<Window> cover) {
        var sorted = cover.stream().sorted(Comparator.comparing(Window::fromInclusive)).toList();
        var result = new ArrayList<Window>();
        for (var window : windows) {
            var cursor = window.fromInclusive();
            for (var hole : sorted) {
                if (!hole.toExclusive().isAfter(cursor) || !hole.fromInclusive().isBefore(window.toExclusive()))
                    continue;
                if (hole.fromInclusive().isAfter(cursor)) result.add(new Window(cursor, hole.fromInclusive()));
                if (hole.toExclusive().isAfter(cursor)) cursor = hole.toExclusive();
            }
            if (cursor.isBefore(window.toExclusive())) result.add(new Window(cursor, window.toExclusive()));
        }
        return List.copyOf(result);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
