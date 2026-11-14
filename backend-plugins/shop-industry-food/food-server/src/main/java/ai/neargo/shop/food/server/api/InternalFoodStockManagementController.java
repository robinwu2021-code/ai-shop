package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.food.service.FoodStockReconciliationService.*;
import java.time.LocalDateTime;
import java.util.List;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;

/** Same local services as embedded mode, protected by FOOD token and signed scope. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class InternalFoodStockManagementController extends FoodStockInternalErrors {
    private final FoodStockItemService items;
    private final FoodStockBalanceService balances;
    private final FoodStockSyncService sync;
    private final FoodStockReconciliationService reconciliation;
    public InternalFoodStockManagementController(FoodStockItemService items,FoodStockBalanceService balances,
            FoodStockSyncService sync,FoodStockReconciliationService reconciliation) {
        this.items=items;this.balances=balances;this.sync=sync;this.reconciliation=reconciliation;
    }
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/items/create")
    public ItemVO createItem(@RequestBody FoodStockManagementInternalApi.ItemCreate r) {return items.createItem(r.entityNo(),r.storeNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/items/update")
    public ItemVO updateItem(@RequestBody FoodStockManagementInternalApi.ItemUpdate r) {return items.updateItem(r.entityNo(),r.storeNo(),r.itemNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/items/get")
    public ItemVO getItem(@RequestBody FoodStockManagementInternalApi.ItemQuery r) {return items.getItem(r.entityNo(),r.storeNo(),r.itemNo());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/items/list")
    public PageData<ItemVO> listItems(@RequestBody FoodStockManagementInternalApi.ItemsQuery r) {return items.listItems(r.entityNo(),r.storeNo(),r.page(),r.size(),r.keyword(),r.kind(),r.status());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/balances/initialize")
    public BalanceVO initialize(@RequestBody FoodStockManagementInternalApi.InitializeCommand r) {return balances.initialize(r.entityNo(),r.storeNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/balances/policy")
    public BalanceVO changePolicy(@RequestBody FoodStockManagementInternalApi.PolicyCommand r) {return balances.changePolicy(r.entityNo(),r.storeNo(),r.itemNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/balances/adjust")
    public BalanceVO adjust(@RequestBody FoodStockManagementInternalApi.AdjustCommand r) {return balances.adjust(r.entityNo(),r.storeNo(),r.itemNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/balances/get")
    public BalanceVO getBalance(@RequestBody FoodStockManagementInternalApi.ItemQuery r) {return balances.getBalance(r.entityNo(),r.storeNo(),r.itemNo());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/balances/list")
    public PageData<BalanceVO> listBalances(@RequestBody FoodStockManagementInternalApi.PageQuery r) {return balances.listBalances(r.entityNo(),r.storeNo(),r.page(),r.size());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/movements/list")
    public PageData<MovementVO> listMovements(@RequestBody FoodStockManagementInternalApi.MovementsQuery r) {return balances.listMovements(r.entityNo(),r.storeNo(),r.page(),r.size(),r.itemNo(),r.action(),r.from(),r.to());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/config")
    public ConfigVO config(@RequestBody FoodStockManagementInternalApi.StoreQuery r) {return sync.config(r.entityNo(),r.storeNo());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/preflight")
    public Preflight preflight(@RequestBody FoodStockManagementInternalApi.StoreQuery r) {return sync.preflight(r.entityNo(),r.storeNo());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/configure")
    public ConfigVO configure(@RequestBody FoodStockManagementInternalApi.ConfigWrite r) {return sync.configure(r.entityNo(),r.storeNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/mappings")
    public PageData<MappingVO> mappings(@RequestBody FoodStockManagementInternalApi.PageQuery r) {return sync.mappings(r.entityNo(),r.storeNo(),r.page(),r.size());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/map")
    public MappingVO map(@RequestBody FoodStockManagementInternalApi.MappingWrite r) {return sync.map(r.entityNo(),r.storeNo(),r.itemNo(),r.actorNo(),r.input(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/deliveries")
    public PageData<DeliveryVO> deliveries(@RequestBody FoodStockManagementInternalApi.DeliveryQuery r) {return sync.deliveries(r.entityNo(),r.storeNo(),r.page(),r.size(),r.status(),r.itemNo());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/retry")
    public DeliveryVO retry(@RequestBody FoodStockManagementInternalApi.RetryCommand r) {return sync.retry(r.entityNo(),r.storeNo(),r.syncNo(),r.actorNo(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/reconcile")
    public ReconciliationVO create(@RequestBody FoodStockManagementInternalApi.ReconcileCommand r) {return reconciliation.create(r.entityNo(),r.storeNo(),r.actorNo(),r.itemNos(),r.key());}
    @PostMapping(FoodStockManagementInternalApi.ROOT+"/sync/reconciliation")
    public ReconciliationVO get(@RequestBody FoodStockManagementInternalApi.ReconcileQuery r) {return reconciliation.get(r.entityNo(),r.storeNo(),r.number(),r.page(),r.size());}
}
