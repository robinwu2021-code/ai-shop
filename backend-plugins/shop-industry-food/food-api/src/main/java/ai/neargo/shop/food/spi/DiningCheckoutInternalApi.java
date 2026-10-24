package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface DiningCheckoutInternalApi {
    String ROOT = "/internal/food/settlements";
    record Create(CartAccess access, String batchNo, CreateDiningSettlementReq request, String key) { }
    record Reference(CartAccess access, String settlementNo) { }
    record Pay(CartAccess access, String settlementNo, PayDiningSettlementReq request, String key) { }
    record Cancel(CartAccess access, String settlementNo, DiningSettlementVersionReq request) { }
    record StaffGet(String entity, String store, String actor, String settlementNo) { }
    @PostExchange(ROOT + "/create") DiningSettlementVO customerCreate(@RequestBody Create request);
    @PostExchange(ROOT + "/get") DiningSettlementVO customerGet(@RequestBody Reference request);
    @PostExchange(ROOT + "/pay") DiningSettlementVO customerPay(@RequestBody Pay request);
    @PostExchange(ROOT + "/query") DiningSettlementVO customerQuery(@RequestBody Reference request);
    @PostExchange(ROOT + "/finalize") DiningSettlementVO customerFinalize(@RequestBody Reference request);
    @PostExchange(ROOT + "/cancel") DiningSettlementVO customerCancel(@RequestBody Cancel request);
    @PostExchange(ROOT + "/staff/get") DiningSettlementVO staffGet(@RequestBody StaffGet request);
    record Original(String entity, String store, String number) { }
    @PostExchange(ROOT + "/recover-original") DiningSettlementVO recoverOriginal(@RequestBody Original request);
}
