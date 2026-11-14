package ai.neargo.shop.food.port;

import ai.neargo.shop.food.dto.*;
import java.util.function.Supplier;

/** Process-local prepared commands. Never serialized or accepted from an HTTP caller. */
public interface OfflineCartReplayPort {
    Supplier<CartResultVO> prepareOfflineCreate(String store,CartAccess access,CreateCustomerCartReq request,String key);
    Supplier<CartVersionVO> prepareOfflineReplace(String cart,String source,CartAccess access,ReplaceCartLineReq request,String key);
}
