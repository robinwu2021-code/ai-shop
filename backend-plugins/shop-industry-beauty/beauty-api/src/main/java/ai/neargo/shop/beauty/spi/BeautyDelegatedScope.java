package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** An authenticated platform delegates coordinates; a service credential alone must not supply missing scope. */
public final class BeautyDelegatedScope {
    private BeautyDelegatedScope() { }
    /** Missing delegation never falls back to a default entity, store or SYSTEM actor. */
    public static void require(String entity, String store, String actor) {
        if (!valid(entity, 32) || !valid(store, 32) || !valid(actor, 64)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
