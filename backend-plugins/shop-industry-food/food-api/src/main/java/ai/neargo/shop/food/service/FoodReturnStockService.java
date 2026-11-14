package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import java.math.BigDecimal;

/** Physical disposition is explicit and independent of the payment/refund status. */
public interface FoodReturnStockService {
    record Apply(String returnItemNo,String productionEventNo,BigDecimal dishQty,String disposition,
                 String reasonCode,String reason,String evidenceRef) {}
    record DispositionVO(String operationNo,String returnNo,String returnItemNo,String productionEventNo,
                         BigDecimal dishQty,String disposition,String status,String stockCommandNo,String lastErrorCode) {}
    DispositionVO apply(String entity,String store,String returnNo,String actor,Apply input,String key);
    PageData<DispositionVO> list(String entity,String store,String returnNo,int page,int size);
    DispositionVO retry(String entity,String store,String returnNo,String operationNo,String actor);
    int recover(int limit);
}
