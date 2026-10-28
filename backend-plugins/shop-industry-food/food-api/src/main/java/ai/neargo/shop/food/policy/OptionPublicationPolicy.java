package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import java.math.BigDecimal;

/** A bounded feasibility proof prevents publishing a group whose constraints admit no customer selection. */
public final class OptionPublicationPolicy {
    private OptionPublicationPolicy() { }

    private static void validateDefaults(BigDecimal min, BigDecimal max, ReplaceOptionGroupItemsReq input) {
        if (min == null || max == null || min.signum() < 0 || min.compareTo(max) > 0
                || min.stripTrailingZeros().scale() > 6 || max.stripTrailingZeros().scale() > 6
                || max.compareTo(new BigDecimal("999999999999.999999")) > 0 || input.items().isEmpty()) throw bad();
        BigDecimal defaults = input.items().stream().map(OptionItemInput::defaultQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (defaults.compareTo(max) > 0) throw bad();
    }

    /** Legacy publication still asks only for one selection; channel capture additionally proves every visible choice. */
    public static void validate(BigDecimal min, BigDecimal max, ReplaceOptionGroupItemsReq input) {
        var normalized = OptionSelectionPolicy.normalize(input);
        validateDefaults(min, max, normalized);
        var result = calculate(min, max, normalized, new SelectionFeasibility.Budget(10000), false);
        if (result.status() != SelectionFeasibility.Status.PROVEN) throw bad();
    }

    /** Normalization and defaults remain owner rules; a shared catalog budget prevents unbounded multi-menu work. */
    public static SelectionFeasibility.Result prove(BigDecimal min, BigDecimal max,
            ReplaceOptionGroupItemsReq input, SelectionFeasibility.Budget budget) {
        var normalized = OptionSelectionPolicy.normalize(input);
        validateDefaults(min, max, normalized);
        for (var item : normalized.items())
            if (item.defaultQty().signum() > 0) CartInputPolicy.quantity(item.defaultQty());
        return calculate(min, max, normalized, budget, true);
    }

    private static SelectionFeasibility.Result calculate(BigDecimal min, BigDecimal max,
            ReplaceOptionGroupItemsReq input, SelectionFeasibility.Budget budget, boolean all) {
        return SelectionFeasibility.prove(min, max, input.items().stream()
                .map(i -> new SelectionFeasibility.Choice(i.optionItemNo(), i.minQty(), i.maxQty())).toList(),
                input.selectionRules(), budget, all);
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
