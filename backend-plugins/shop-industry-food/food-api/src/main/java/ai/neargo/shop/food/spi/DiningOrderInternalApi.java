package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Original placement keys are retained across the standalone gateway. */
public interface DiningOrderInternalApi {
    String ROOT = "/internal/food/order-batches";
    @PostExchange(ROOT + "/place") DiningOrderBatchVO customerPlace(@RequestBody Place request);
    @PostExchange(ROOT + "/customer/get") DiningOrderBatchVO customerGet(@RequestBody CustomerGet request);
    @PostExchange(ROOT + "/staff/get") DiningOrderBatchVO staffGet(@RequestBody StaffGet request);
    record Place(String store, CartAccess access, CreateDiningOrderReq request, String key) {
        @Override public String toString() { return "DiningOrderPlace[REDACTED]"; }
    }
    record CustomerGet(CartAccess access, String batchNo) {
        @Override public String toString() { return "DiningOrderGet[REDACTED]"; }
    }
    record StaffGet(String entity, String store, String actor, String batchNo) { }
    record Original(String entity, String store, String number) { }
    @PostExchange(ROOT + "/recover-original") DiningOrderBatchVO recoverOriginal(@RequestBody Original request);
}
