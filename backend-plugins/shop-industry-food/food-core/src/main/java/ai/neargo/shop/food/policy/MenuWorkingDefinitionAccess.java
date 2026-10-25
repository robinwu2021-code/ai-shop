package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;

/** Internal aggregate access; the caller owns the local read/write transaction and publication scope. */
public interface MenuWorkingDefinitionAccess {
    MenuConfigResultVO capture(String entity, String store, String menu, boolean lock);
    default long currentVersion(String entity, String store, String menu, boolean lock) {
        return capture(entity, store, menu, lock).config().version();
    }
    void advance(String entity, String store, String menu, long version, FnbConfigRevision revision);
}
