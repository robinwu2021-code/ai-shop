package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.math.BigDecimal;
import java.util.HashSet;

/** Rejects precision loss before SQL; draft quantities do not imply executable fractional inventory. */
public final class RecipeDefinitionPolicy {
    private RecipeDefinitionPolicy() { }
    public static void identifier(String value) {
        if (!MaterialCatalogFacts.validKey(value, 32)) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    public static BigDecimal quantity(BigDecimal value, boolean positive) {
        if (value == null || value.signum() < (positive ? 1 : 0)) throw BizException.of(ErrorCode.BAD_REQUEST);
        var n = value.stripTrailingZeros();
        if (n.scale() > 6 || (long) n.precision() - n.scale() > 12) throw BizException.of(ErrorCode.BAD_REQUEST);
        return n.scale() < 0 ? n.setScale(0) : n;
    }
    private static void unit(String value) {
        if (!"EA".equals(value)) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    public static CreateRecipeReq normalize(CreateRecipeReq req) {
        if (req == null || req.recipeCode() == null || !req.recipeCode().matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        identifier(req.skuNo()); unit(req.yieldUnit());
        return new CreateRecipeReq(req.skuNo(), req.recipeCode(), quantity(req.yieldQty(), true), req.yieldUnit());
    }
    public static ReplaceRecipeDefinitionReq normalize(ReplaceRecipeDefinitionReq req) {
        if (req == null || req.version() == null || req.version() < 0 || req.version() == Long.MAX_VALUE
                || req.lines() == null || req.lines().size() > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        unit(req.yieldUnit());
        var seen = new HashSet<String>();
        var lines = req.lines().stream().map(line -> {
            if (line == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            if (line.lineNo() != null) {
                identifier(line.lineNo());
                if (!seen.add(line.lineNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            identifier(line.itemRef());
            if (!MaterialCatalogFacts.validKey(line.unit(), 16)) throw BizException.of(ErrorCode.BAD_REQUEST);
            var loss = quantity(line.lossRate(), false);
            if (loss.compareTo(BigDecimal.ONE) >= 0) throw BizException.of(ErrorCode.BAD_REQUEST);
            return new RecipeLineInput(line.lineNo(), line.itemRef(), quantity(line.qty(), true), line.unit(), loss);
        }).toList();
        return new ReplaceRecipeDefinitionReq(req.version(), quantity(req.yieldQty(), true), req.yieldUnit(), lines,
                RecipeAdjustmentPolicy.normalize(req.adjustments()));
    }
}
