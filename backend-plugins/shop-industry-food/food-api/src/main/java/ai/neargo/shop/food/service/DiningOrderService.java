package ai.neargo.shop.food.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.food.dto.CancelDiningOrderReq;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.CreateStaffDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;

/** Original-key restaurant placement and source binding; core remains the order and payment owner. */
public interface DiningOrderService {
    /** Current-store staff placement does not authorize any debit or invent an anonymous customer. */
    default DiningOrderBatchVO staffPlace(String entity, String store, String actor,
            CreateStaffDiningOrderReq request, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    /** Persists an original cancellation intention; absence or timeout is never proof of cancellation. */
    default DiningOrderBatchVO staffCancel(String entity, String store, String actor, String batchNo,
            CancelDiningOrderReq request, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    /** Frozen cancellation source only; never rechecks current menu, quota, stock, rules or taxes. */
    default Snapshot authorizeCancellation(Command command, String key, String fulfillment) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq request, String key);
    DiningOrderBatchVO customerGet(CartAccess access, String batchNo);
    DiningOrderBatchVO staffGet(String entity, String store, String actor, String batchNo);
    /** Backend replay of this persisted source and original consent; no current login or fresh financial command. */
    DiningOrderBatchVO recoverOriginal(String entity, String store, String number);
}
