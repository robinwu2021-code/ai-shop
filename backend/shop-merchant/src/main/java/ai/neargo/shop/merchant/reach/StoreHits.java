package ai.neargo.shop.merchant.reach;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个消费者画像对<b>一家店</b>的范围命中结果（ADR-034）。只记「哪几条范围项命中了」，不做任何判定——
 * 判定在 {@link ReachRule} 一处。
 *
 * <ul>
 *   <li>{@link #includeAreaNos}：确定命中的纳入项（行政级/小区级，或多边形的<b>内部</b> cell）</li>
 *   <li>{@link #excludeAreaNos}：确定命中的排除项</li>
 *   <li>{@link #boundaryIncludeAreaNos} / {@link #boundaryExcludeAreaNos}：多边形<b>边界</b> cell 命中，
 *       「可能在内」，判定前要用多边形几何精判</li>
 * </ul>
 * 「不限」不在这里：它是门店属性，走快照。
 */
public record StoreHits(Set<String> includeAreaNos, Set<String> excludeAreaNos,
                        Set<String> boundaryIncludeAreaNos, Set<String> boundaryExcludeAreaNos) {

    public static StoreHits empty() {
        return new StoreHits(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 可变累加器：两种查找器逐行往里加 */
    public static final class Builder {
        private final Set<String> include = new LinkedHashSet<>();
        private final Set<String> exclude = new LinkedHashSet<>();
        private final Set<String> boundaryInclude = new LinkedHashSet<>();
        private final Set<String> boundaryExclude = new LinkedHashSet<>();

        public Builder area(String areaNo, boolean exclude) {
            (exclude ? this.exclude : this.include).add(areaNo);
            return this;
        }

        public Builder cell(String areaNo, boolean exclude, boolean boundary) {
            if (boundary) {
                (exclude ? boundaryExclude : boundaryInclude).add(areaNo);
            } else {
                (exclude ? this.exclude : this.include).add(areaNo);
            }
            return this;
        }

        public StoreHits build() {
            return new StoreHits(Set.copyOf(include), Set.copyOf(exclude),
                    Set.copyOf(boundaryInclude), Set.copyOf(boundaryExclude));
        }
    }
}
