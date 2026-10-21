package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates selection feasibility without calculating money or flattening an inventory tree. */
public final class BundleDefinitionPolicy {
    private BundleDefinitionPolicy() { }

    public static ReplaceBundleDefinitionReq normalize(ReplaceBundleDefinitionReq input) {
        if (input == null || input.version() == null || input.version() < 0 || input.groups() == null
                || input.choices() == null || input.groups().size() > 30 || input.choices().size() > 100) bad();
        var groupIds = new HashSet<String>();
        var groups = input.groups().stream().map(g -> {
            if (g == null) bad();
            identifier(g.groupNo());
            if (!groupIds.add(g.groupNo()) || g.name() == null || g.name().isBlank()
                    || g.name().trim().length() > 64 || g.sortNo() == null || g.sortNo() < 0) bad();
            if (g.baseSkuNo() != null) identifier(g.baseSkuNo());
            var min = quantity(g.minQty(), 6, false); var max = quantity(g.maxQty(), 6, false);
            if (min.compareTo(max) > 0) bad();
            return new BundleGroupInput(g.groupNo(), g.name().trim(), min, max, g.baseSkuNo(), g.sortNo());
        }).sorted(Comparator.comparing(BundleGroupInput::sortNo).thenComparing(BundleGroupInput::groupNo)).toList();
        Map<String, BundleGroupInput> byGroup = groups.stream()
                .collect(Collectors.toMap(BundleGroupInput::groupNo, Function.identity()));
        var choiceIds = new HashSet<String>(); var members = new HashSet<List<String>>();
        var choices = input.choices().stream().map(c -> {
            if (c == null) bad();
            identifier(c.choiceNo()); identifier(c.groupNo()); identifier(c.skuNo());
            var group = byGroup.get(c.groupNo());
            if (!choiceIds.add(c.choiceNo()) || !members.add(List.of(c.groupNo(), c.skuNo())) || group == null
                    || c.sortNo() == null || c.sortNo() < 0 || c.priceMode() == null
                    || !Set.of("INCLUDED", "ADD_SKU", "DIFF_BASE").contains(c.priceMode())
                    || "DIFF_BASE".equals(c.priceMode()) && group.baseSkuNo() == null) bad();
            var min = quantity(c.minQty(), 6, false); var max = quantity(c.maxQty(), 6, true);
            var def = quantity(c.defaultQty(), 6, false); var units = quantity(c.unitsPerPick(), 3, true);
            if (min.compareTo(max) > 0 || def.compareTo(max) > 0
                    || def.signum() > 0 && def.compareTo(min) < 0) bad();
            return new BundleChoiceInput(c.choiceNo(), c.groupNo(), c.skuNo(), units, c.priceMode(),
                    def, min, max, c.sortNo());
        }).sorted(Comparator.comparing(BundleChoiceInput::groupNo).thenComparing(BundleChoiceInput::sortNo)
                .thenComparing(BundleChoiceInput::choiceNo)).toList();
        for (var g : groups) {
            var children = choices.stream().filter(c -> g.groupNo().equals(c.groupNo())).toList();
            BigDecimal min = BigDecimal.ZERO, max = BigDecimal.ZERO, defaults = BigDecimal.ZERO;
            for (var c : children) { min = min.add(c.minQty()); max = max.add(c.maxQty());
                defaults = defaults.add(c.defaultQty().max(c.minQty())); }
            if (children.isEmpty() || min.compareTo(g.maxQty()) > 0 || defaults.compareTo(g.maxQty()) > 0
                    || max.compareTo(g.minQty()) < 0) bad();
        }
        return new ReplaceBundleDefinitionReq(input.version(), groups, choices);
    }

    public static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) bad();
    }

    private static BigDecimal quantity(BigDecimal value, int scale, boolean positive) {
        if (value == null || value.signum() < 0 || positive && value.signum() == 0
                || value.compareTo(new BigDecimal(scale == 3 ? "999999999.999" : "999999999999.999999")) > 0) bad();
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() > scale) bad();
        return normalized;
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
}
