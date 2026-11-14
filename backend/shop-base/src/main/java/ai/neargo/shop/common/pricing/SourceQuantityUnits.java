package ai.neargo.shop.common.pricing;

import java.util.Set;

/**
 * Existing sales EA and inventory PIECE both denote one piece; no weight, volume or ratio
 * conversion is implied.
 */
public final class SourceQuantityUnits {
    private static final Set<String> PIECE_UNITS = Set.of("EA", "PIECE");

    private SourceQuantityUnits() {}

    public static boolean same(String left, String right) {
        return left != null
                && !left.isBlank()
                && right != null
                && !right.isBlank()
                && (left.equals(right)
                        || PIECE_UNITS.contains(left) && PIECE_UNITS.contains(right));
    }
}
