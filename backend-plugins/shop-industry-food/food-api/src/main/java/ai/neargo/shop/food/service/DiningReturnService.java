package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.dto.DiningReturnVO;

/** Consumer original MAIN-line returns through core remaining-capacity policy; staff reads grant no execution power. */
public interface DiningReturnService {
    DiningReturnVO customerApply(String store, CartAccess access, DiningReturnApplyReq request, String key);
    DiningReturnVO customerGet(CartAccess access, String returnNo);
    DiningReturnVO customerCancel(CartAccess access, String returnNo);
    DiningReturnVO staffGet(String entity, String store, String actor, String returnNo);
    /** Backend replay of this persisted source and original consent; no current login or fresh financial command. */
    DiningReturnVO recoverOriginal(String entity, String store, String number);
}
