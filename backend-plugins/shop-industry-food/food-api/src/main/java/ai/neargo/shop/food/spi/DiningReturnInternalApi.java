package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.dto.DiningReturnVO;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** FOOD service-token transport preserves trusted login-derived buyer credentials and original source scope. */
public interface DiningReturnInternalApi {
    String ROOT = "/internal/food/dining-returns";
    @PostExchange(ROOT + "/customer/apply") DiningReturnVO customerApply(@RequestBody CustomerApply request);
    @PostExchange(ROOT + "/customer/get") DiningReturnVO customerGet(@RequestBody CustomerReference request);
    @PostExchange(ROOT + "/customer/cancel") DiningReturnVO customerCancel(@RequestBody CustomerReference request);
    @PostExchange(ROOT + "/staff/get") DiningReturnVO staffGet(@RequestBody StaffReference request);
    record CustomerApply(String store, CartAccess access, DiningReturnApplyReq request, String key) {
        @JsonAnySetter public void unknown(String name, Object value) { reject(); }
    }
    record CustomerReference(CartAccess access, String returnNo) {
        @JsonAnySetter public void unknown(String name, Object value) { reject(); }
    }
    record StaffReference(String entity, String store, String actor, String returnNo) {
        @JsonAnySetter public void unknown(String name, Object value) { reject(); }
    }
    private static void reject() { throw new IllegalArgumentException("Unknown dining return transport field"); }
    record Original(String entity, String store, String number) { }
    @PostExchange(ROOT + "/recover-original") DiningReturnVO recoverOriginal(@RequestBody Original request);
}
