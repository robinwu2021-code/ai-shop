package ai.neargo.shop.food.commerce.policy;

import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.service.MenuChannelBindingService;

/**
 * A binding frozen ACTIVE in a published menu sells on its connection only while the local row is
 * still that same ACTIVE binding; disabling or rebinding it delists at once, without republishing.
 */
public final class FoodLiveChannelBinding {
    private FoodLiveChannelBinding() {}

    public static boolean live(
            MenuChannelBindingService bindings,
            String entity,
            String store,
            MenuChannelBindingVO frozen) {
        if (bindings == null) throw new IllegalStateException("Missing original channel binding reader");
        var current = bindings.getBinding(entity, store, frozen.bindingNo());
        return current != null
                && "ACTIVE".equals(frozen.status())
                && "ACTIVE".equals(current.status())
                && frozen.bindingNo().equals(current.bindingNo())
                && frozen.menuNo().equals(current.menuNo())
                && frozen.storeNo().equals(current.storeNo())
                && frozen.provider().equals(current.provider())
                && frozen.channelAccountRef().equals(current.channelAccountRef())
                && frozen.externalStoreRef().equals(current.externalStoreRef());
    }
}
