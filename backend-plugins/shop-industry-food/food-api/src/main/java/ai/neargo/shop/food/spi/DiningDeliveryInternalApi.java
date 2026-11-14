package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keeps the DiningDeliveryInternalApi protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
public interface DiningDeliveryInternalApi {
    String ROOT = "/internal/food/delivery-source";

    record StaffSave(
            String entityNo,
            String storeNo,
            String actorNo,
            String cartNo,
            SaveReq definition,
            String key) {}

    record CustomerSave(String cartNo, CartAccess access, SaveReq definition, String key) {}

    record Source(String entityNo, String storeNo, String cartNo) {}

    record CustomerQuery(String cartNo, CartAccess access) {}

    @PostExchange(ROOT + "/save-staff")
    DeliverySnapshot saveStaff(@RequestBody StaffSave command);

    @PostExchange(ROOT + "/save-customer")
    DeliverySnapshot saveCustomer(@RequestBody CustomerSave command);

    @PostExchange(ROOT + "/get-staff")
    DeliverySnapshot getStaff(@RequestBody Source query);

    @PostExchange(ROOT + "/get-customer")
    DeliverySnapshot getCustomer(@RequestBody CustomerQuery query);

    @PostExchange(ROOT + "/source")
    DeliverySnapshot get(@RequestBody Source query);
}
