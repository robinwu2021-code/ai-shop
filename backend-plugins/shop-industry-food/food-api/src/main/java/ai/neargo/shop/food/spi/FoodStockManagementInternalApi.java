package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal transport for the existing store-authorized management Services. */
public interface FoodStockManagementInternalApi {
    String ROOT="/internal/food/stock-management";
    record ItemCreate(String entityNo, String storeNo, String actorNo, CreateItem input, String key) {}
    record ItemUpdate(String entityNo, String storeNo, String itemNo, String actorNo, UpdateItem input, String key) {}
    record ItemQuery(String entityNo, String storeNo, String itemNo) {}
    record ItemsQuery(String entityNo, String storeNo, int page, int size, String keyword, String kind, String status) {}
    record InitializeCommand(String entityNo, String storeNo, String actorNo, Initialize input, String key) {}
    record PolicyCommand(String entityNo, String storeNo, String itemNo, String actorNo, Policy input, String key) {}
    record AdjustCommand(String entityNo, String storeNo, String itemNo, String actorNo, Adjust input, String key) {}
    record PageQuery(String entityNo, String storeNo, int page, int size) {}
    record MovementsQuery(String entityNo, String storeNo, int page, int size, String itemNo, String action, LocalDateTime from, LocalDateTime to) {}
    record StoreQuery(String entityNo, String storeNo) {}
    record ConfigWrite(String entityNo, String storeNo, String actorNo, ConfigCommand input, String key) {}
    record MappingWrite(String entityNo, String storeNo, String itemNo, String actorNo, MappingCommand input, String key) {}
    record DeliveryQuery(String entityNo, String storeNo, int page, int size, String status, String itemNo) {}
    record RetryCommand(String entityNo, String storeNo, String syncNo, String actorNo, String key) {}
    record ReconcileCommand(String entityNo, String storeNo, String actorNo, List<String> itemNos, String key) {}
    record ReconcileQuery(String entityNo, String storeNo, String number, int page, int size) {}
    @PostExchange(ROOT+"/items/create") ItemVO createItem(@RequestBody ItemCreate request);
    @PostExchange(ROOT+"/items/update") ItemVO updateItem(@RequestBody ItemUpdate request);
    @PostExchange(ROOT+"/items/get") ItemVO getItem(@RequestBody ItemQuery request);
    @PostExchange(ROOT+"/items/list") PageData<ItemVO> listItems(@RequestBody ItemsQuery request);
    @PostExchange(ROOT+"/balances/initialize") BalanceVO initialize(@RequestBody InitializeCommand request);
    @PostExchange(ROOT+"/balances/policy") BalanceVO changePolicy(@RequestBody PolicyCommand request);
    @PostExchange(ROOT+"/balances/adjust") BalanceVO adjust(@RequestBody AdjustCommand request);
    @PostExchange(ROOT+"/balances/get") BalanceVO getBalance(@RequestBody ItemQuery request);
    @PostExchange(ROOT+"/balances/list") PageData<BalanceVO> listBalances(@RequestBody PageQuery request);
    @PostExchange(ROOT+"/movements/list") PageData<MovementVO> listMovements(@RequestBody MovementsQuery request);
    @PostExchange(ROOT+"/sync/config") ConfigVO config(@RequestBody StoreQuery request);
    @PostExchange(ROOT+"/sync/preflight") Preflight preflight(@RequestBody StoreQuery request);
    @PostExchange(ROOT+"/sync/configure") ConfigVO configure(@RequestBody ConfigWrite request);
    @PostExchange(ROOT+"/sync/mappings") PageData<MappingVO> mappings(@RequestBody PageQuery request);
    @PostExchange(ROOT+"/sync/map") MappingVO map(@RequestBody MappingWrite request);
    @PostExchange(ROOT+"/sync/deliveries") PageData<DeliveryVO> deliveries(@RequestBody DeliveryQuery request);
    @PostExchange(ROOT+"/sync/retry") DeliveryVO retry(@RequestBody RetryCommand request);
    @PostExchange(ROOT+"/sync/reconcile") ReconciliationVO create(@RequestBody ReconcileCommand request);
    @PostExchange(ROOT+"/sync/reconciliation") ReconciliationVO get(@RequestBody ReconcileQuery request);
}
