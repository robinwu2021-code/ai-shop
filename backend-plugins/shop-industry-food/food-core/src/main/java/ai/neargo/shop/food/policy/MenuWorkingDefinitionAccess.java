package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;

/** Internal aggregate access; the caller owns the local read/write transaction and publication scope. */
public interface MenuWorkingDefinitionAccess {
    MenuConfigResultVO capture(String entity, String store, String menu, boolean lock);
    default long currentVersion(String entity, String store, String menu, boolean lock) {
        return capture(entity, store, menu, lock).config().version();
    }
    /** Live head status of a menu bound to the store, or null when this access cannot tell. */
    default String headStatus(String entity, String store, String menu) {
        return null;
    }
    void advance(String entity, String store, String menu, long version, FnbConfigRevision revision);
}
