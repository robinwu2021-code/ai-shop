package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;

/** Single full-root checkout through original core payment and inventory owners. */
public interface DiningCheckoutService {
    DiningSettlementVO customerCreate(CartAccess access, String batchNo, CreateDiningSettlementReq request, String key);
    DiningSettlementVO customerGet(CartAccess access, String settlementNo);
    DiningSettlementVO customerPay(CartAccess access, String settlementNo, PayDiningSettlementReq request, String key);
    DiningSettlementVO customerQuery(CartAccess access, String settlementNo);
    DiningSettlementVO customerFinalize(CartAccess access, String settlementNo);
    DiningSettlementVO customerCancel(CartAccess access, String settlementNo, DiningSettlementVersionReq request);
    DiningSettlementVO staffGet(String entity, String store, String actor, String settlementNo);
    /** Backend replay of this persisted source and original consent; no current login or fresh financial command. */
    DiningSettlementVO recoverOriginal(String entity, String store, String number);
}
