package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.SelectionRules.SelectionDependency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, typed selection constraints shared by management validation and later publication. */
public final class OptionSelectionPolicy {
    private OptionSelectionPolicy() { }

    /** Canonicalization makes equivalent retries stable while rejecting ambiguous references and impossible rules. */
    public static ReplaceOptionGroupItemsReq normalize(ReplaceOptionGroupItemsReq req) {
        if (req == null || req.version() == null || req.version() < 0 || req.items() == null
                || req.items().size() > 100 || req.goodsNos() == null || req.goodsNos().size() > 100
                || req.selectionRules() == null) throw bad();
        var ids = new HashSet<String>();
        var sources = new HashSet<String>();
        var normalized = new ArrayList<OptionItemInput>();
        for (var i : req.items()) {
            if (i == null || !key(i.optionItemNo(), 32) || !ids.add(i.optionItemNo())
                    || i.sortNo() == null || i.sortNo() < 0) throw bad();
            boolean spec = "SPEC_VALUE".equals(i.sourceKind()), modifier = "MODIFIER".equals(i.sourceKind());
            if ((!spec && !modifier) || spec && (!key(i.specValueNo(), 64) || i.modifierNo() != null)
                    || modifier && (!key(i.modifierNo(), 32) || i.specValueNo() != null)) throw bad();
            if (!sources.add(i.sourceKind() + ":" + (spec ? i.specValueNo() : i.modifierNo()))) throw bad();
            var min = quantity(i.minQty());
            var max = quantity(i.maxQty());
            var def = quantity(i.defaultQty());
            if (min.compareTo(def) > 0 || def.compareTo(max) > 0) throw bad();
            normalized.add(new OptionItemInput(i.optionItemNo(), i.sourceKind(), i.specValueNo(), i.modifierNo(),
                    min, max, def, i.sortNo()));
        }
        normalized.sort(Comparator.comparingInt(OptionItemInput::sortNo).thenComparing(OptionItemInput::optionItemNo));
        var goods = new HashSet<String>();
        for (String g : req.goodsNos()) if (!key(g, 32) || !goods.add(g)) throw bad();
        var rules = req.selectionRules();
        if (rules.mutexSets() == null || rules.requires() == null || rules.mutexSets().size() > 100
                || rules.requires().size() > 100) throw bad();
        var mutex = new ArrayList<List<String>>();
        var uniqueMutex = new HashSet<List<String>>();
        for (var set : rules.mutexSets()) {
            var ordered = references(set, ids);
            if (ordered.size() < 2 || !uniqueMutex.add(ordered)) throw bad();
            mutex.add(ordered);
        }
        mutex.sort(Comparator.comparing(Object::toString));
        var graph = new HashMap<String, List<String>>();
        for (var dependency : rules.requires()) {
            if (dependency == null || !ids.contains(dependency.optionItemNo())
                    || graph.containsKey(dependency.optionItemNo())) throw bad();
            var needs = references(dependency.requiresItemNos(), ids);
            if (needs.isEmpty() || needs.contains(dependency.optionItemNo())) throw bad();
            graph.put(dependency.optionItemNo(), needs);
        }
        for (String id : ids) {
            var closure = new HashSet<String>();
            visit(id, graph, new HashSet<>(), closure);
            exclusive(closure, mutex);
        }
        var defaults = new HashSet<String>();
        var required = new HashSet<String>();
        for (var i : normalized) {
            if (i.defaultQty().signum() > 0) defaults.add(i.optionItemNo());
            if (i.minQty().signum() > 0) required.add(i.optionItemNo());
        }
        exclusive(defaults, mutex);
        exclusive(required, mutex);
        for (String id : defaults) {
            var closure = new HashSet<String>(); visit(id, graph, new HashSet<>(), closure);
            if (!defaults.containsAll(closure)) throw bad();
        }
        var deps = graph.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new SelectionDependency(e.getKey(), e.getValue())).toList();
        return new ReplaceOptionGroupItemsReq(req.version(), normalized, goods.stream().sorted().toList(),
                new SelectionRules(mutex, deps));
    }

    private static List<String> references(List<String> input, Set<String> ids) {
        if (input == null || input.size() > 100 || input.stream().anyMatch(x -> !ids.contains(x))
                || new HashSet<>(input).size() != input.size()) throw bad();
        return input.stream().sorted().toList();
    }

    private static void visit(String id, Map<String, List<String>> graph, Set<String> path, Set<String> result) {
        if (path.contains(id)) throw bad();
        if (!result.add(id)) return;
        path.add(id);
        for (String child : graph.getOrDefault(id, List.of())) visit(child, graph, path, result);
        path.remove(id);
    }

    private static void exclusive(Set<String> picked, List<List<String>> mutex) {
        for (var set : mutex) if (set.stream().filter(picked::contains).limit(2).count() > 1) throw bad();
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0) throw bad();
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() > 6 || normalized.compareTo(new BigDecimal("999999999999.999999")) > 0) throw bad();
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }

    private static boolean key(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length && value.equals(value.trim());
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
