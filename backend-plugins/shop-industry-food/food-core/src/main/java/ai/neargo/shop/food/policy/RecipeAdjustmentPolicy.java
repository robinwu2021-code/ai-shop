package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.RecipeAdjustmentInput;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.util.HashSet;
import java.util.List;

/** Draft structure is independent of which mutually exclusive options a guest eventually selects. */
public final class RecipeAdjustmentPolicy {
    private RecipeAdjustmentPolicy() { }

    /** Physical item rows are recreated on normal edits; only the semantic source is stable provenance. */
    public static String optionIdentity(String entity, String group, String option, String kind,
            String spec, String modifier) {
        RecipeDefinitionPolicy.identifier(entity);
        RecipeDefinitionPolicy.identifier(group);
        RecipeDefinitionPolicy.identifier(option);
        boolean valid = "SPEC_VALUE".equals(kind) && MaterialCatalogFacts.validKey(spec, 64) && modifier == null
                || "MODIFIER".equals(kind) && MaterialCatalogFacts.validKey(modifier, 32) && spec == null;
        if (!valid) throw BizException.of(ErrorCode.BAD_REQUEST);
        return RecipePublicationDocuments.optionIdentity(entity, group, option, kind, spec, modifier);
    }

    public static List<RecipeAdjustmentInput> normalize(List<RecipeAdjustmentInput> inputs) {
        if (inputs == null) return null;
        if (inputs.size() > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var ids = new HashSet<String>();
        var targets = new HashSet<List<String>>();
        return inputs.stream().map(input -> {
            if (input == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            if (input.adjustmentNo() != null) {
                RecipeDefinitionPolicy.identifier(input.adjustmentNo());
                if (!ids.add(input.adjustmentNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            RecipeDefinitionPolicy.identifier(input.optionItemNo());
            RecipeDefinitionPolicy.identifier(input.targetLineNo());
            if (!MaterialCatalogFacts.validKey(input.unit(), 16)
                    || !targets.add(List.of(input.optionItemNo(), input.targetLineNo()))
                    || !List.of("SET", "ADD", "REMOVE").contains(input.action() == null ? "" : input.action())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if ("REMOVE".equals(input.action()) && input.qty() != null) throw BizException.of(ErrorCode.BAD_REQUEST);
            return new RecipeAdjustmentInput(input.adjustmentNo(), input.optionItemNo(), input.targetLineNo(),
                    input.action(), "REMOVE".equals(input.action()) ? null
                            : RecipeDefinitionPolicy.quantity(input.qty(), false), input.unit());
        }).toList();
    }
}
