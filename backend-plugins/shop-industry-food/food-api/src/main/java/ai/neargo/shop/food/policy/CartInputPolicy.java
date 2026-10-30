package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import java.math.BigDecimal;
import java.util.HashSet;

/**
 * Bound inputs before authority calls, fingerprints or persistence can accept ambiguous cart
 * identities.
 */
public final class CartInputPolicy {
    private CartInputPolicy() {}

    /** Owner quantity ceiling is shared with selection proofs so catalogs cannot offer an unorderable amount. */
    public static final BigDecimal MAX_SELECTION_QUANTITY = new BigDecimal("999999");

    /** This is per line and per selection kind, not a separate allowance for each bound group. */
    public static final int MAX_SELECTIONS = 100;

    public static void key(String value) {
        require(MaterialCatalogFacts.validKey(value, 32));
    }

    public static void version(Long value) {
        require(value != null && value >= 0 && value < Long.MAX_VALUE);
    }

    public static void replayKey(String value) {
        require(
                value != null
                        && !value.isBlank()
                        && value.length() <= 128
                        && value.equals(value.strip())
                        && value.codePoints().noneMatch(Character::isISOControl));
    }

    public static OrderLineInput line(OrderLineInput line) {
        require(line != null && MaterialCatalogFacts.validKey(line.sourceLineRef(), 64));
        key(line.menuNo());
        key(line.menuItemNo());
        key(line.menuRevisionNo());
        key(line.goodsNo());
        key(line.skuNo());
        if (line.seatNo() != null) key(line.seatNo());
        if (line.courseNo() != null) key(line.courseNo());
        require(
                line.qty() != null
                        && line.qty().signum() > 0
                        && line.qty().stripTrailingZeros().scale() <= 6
                        && line.qty().compareTo(new BigDecimal("999999")) <= 0);
        require(
                line.selections() != null
                        && line.selections().size() <= MAX_SELECTIONS
                        && line.bundleChoices() != null
                        && line.bundleChoices().size() <= MAX_SELECTIONS);
        var seen = new HashSet<String>();
        for (var item : line.selections()) {
            require(item != null);
            key(item.groupNo());
            version(item.configVersion());
            quantity(item.qty());
            require("SPEC_VALUE".equals(item.sourceKind()) || "MODIFIER".equals(item.sourceKind()));
            require(
                    MaterialCatalogFacts.validKey(
                            item.sourceNo(), "SPEC_VALUE".equals(item.sourceKind()) ? 64 : 32));
            require(seen.add(item.groupNo() + ":" + item.sourceKind() + ":" + item.sourceNo()));
        }
        seen.clear();
        for (var choice : line.bundleChoices()) {
            require(choice != null);
            key(choice.choiceNo());
            quantity(choice.qty());
            require(seen.add(choice.choiceNo()));
        }
        return line;
    }

    public static void quantity(BigDecimal value) {
        require(
                value != null
                        && value.signum() > 0
                        && value.stripTrailingZeros().scale() <= 6
                        && value.compareTo(MAX_SELECTION_QUANTITY) <= 0);
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
