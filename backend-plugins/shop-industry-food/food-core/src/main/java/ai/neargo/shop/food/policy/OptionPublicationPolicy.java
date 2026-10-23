package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A bounded feasibility proof prevents publishing a group whose constraints admit no customer selection. */
public final class OptionPublicationPolicy {
    private OptionPublicationPolicy() { }

    public static void validate(BigDecimal min, BigDecimal max, ReplaceOptionGroupItemsReq input) {
        if (min == null || max == null || min.signum() < 0 || min.compareTo(max) > 0
                || min.stripTrailingZeros().scale() > 6 || max.stripTrailingZeros().scale() > 6
                || max.compareTo(new BigDecimal("999999999999.999999")) > 0 || input.items().isEmpty()) throw bad();
        BigDecimal defaults = input.items().stream().map(OptionItemInput::defaultQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (defaults.compareTo(max) > 0) throw bad();
        var solver = new Solver(min, max, input);
        if (!solver.search(new HashSet<>(), new HashSet<>())) throw bad();
    }

    private static final class Solver {
        final BigDecimal min;
        final BigDecimal max;
        final ReplaceOptionGroupItemsReq input;
        final Map<String,OptionItemInput> items = new HashMap<>();
        final Map<String,List<String>> dependencies = new HashMap<>();
        int visited;
        Solver(BigDecimal min, BigDecimal max, ReplaceOptionGroupItemsReq input) {
            this.min = min; this.max = max; this.input = input;
            input.items().forEach(i -> items.put(i.optionItemNo(), i));
            input.selectionRules().requires().forEach(d -> dependencies.put(d.optionItemNo(), d.requiresItemNos()));
        }
        boolean search(Set<String> selected, Set<String> excluded) {
            if (++visited > 10_000) throw bad();
            input.items().stream().filter(i -> i.minQty().signum() > 0)
                    .forEach(i -> selected.add(i.optionItemNo()));
            var pending = new ArrayList<>(selected);
            for (int p = 0; p < pending.size(); p++) {
                for (String dependency : dependencies.getOrDefault(pending.get(p), List.of())) {
                    if (selected.add(dependency)) pending.add(dependency);
                }
            }
            if (selected.stream().anyMatch(excluded::contains)) return false;
            for (var mutex : input.selectionRules().mutexSets()) {
                if (mutex.stream().filter(selected::contains).count() > 1) return false;
            }
            BigDecimal lower = BigDecimal.ZERO, upper = BigDecimal.ZERO, possible = BigDecimal.ZERO;
            boolean needsSlack = false;
            for (var i : input.items()) {
                if (!excluded.contains(i.optionItemNo())) possible = possible.add(i.maxQty());
                if (selected.contains(i.optionItemNo())) {
                    if (i.maxQty().signum() == 0) return false;
                    lower = lower.add(i.minQty()); upper = upper.add(i.maxQty());
                    needsSlack |= i.minQty().signum() == 0;
                }
            }
            if (lower.compareTo(max) > 0 || possible.compareTo(min) < 0) return false;
            if (upper.compareTo(min) >= 0 && (!needsSlack || lower.compareTo(max) < 0)) return true;
            String next = input.items().stream().map(OptionItemInput::optionItemNo)
                    .filter(n -> !selected.contains(n) && !excluded.contains(n)).findFirst().orElse(null);
            if (next == null) return false;
            var include = new HashSet<>(selected); include.add(next);
            if (search(include, new HashSet<>(excluded))) return true;
            var omit = new HashSet<>(excluded); omit.add(next);
            return search(new HashSet<>(selected), omit);
        }
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
