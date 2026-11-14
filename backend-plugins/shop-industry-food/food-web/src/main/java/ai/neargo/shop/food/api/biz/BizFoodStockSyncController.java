package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.service.FoodStockSyncService;
import ai.neargo.shop.food.service.FoodStockSyncService.*;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
@RequestMapping("/biz/x/food/stores/{storeNo}")
@PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
public class BizFoodStockSyncController {
    private final FoodStockSyncService service;private final FoodBizStoreScope scope;
    private final ai.neargo.shop.food.service.FoodStockReconciliationService reconciliation;
    public BizFoodStockSyncController(FoodStockSyncService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator,ai.neargo.shop.food.service.FoodStockReconciliationService reconciliation){this.service=service;this.reconciliation=reconciliation;scope=new FoodBizStoreScope(stores,validator);}
    @GetMapping("/stock-sync/config") public ConfigVO config(@PathVariable String storeNo,HttpServletRequest http){var c=scope.path(storeNo,http);return service.config(c.merchantNo(),storeNo);}
    @PutMapping("/stock-sync/config") public ConfigVO configure(@PathVariable String storeNo,@RequestBody ConfigCommand body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http){var c=scope.path(storeNo,http);return service.configure(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),body,key);}
    @PostMapping("/stock-sync:preflight") public Preflight preflight(@PathVariable String storeNo,HttpServletRequest http){var c=scope.path(storeNo,http);return service.preflight(c.merchantNo(),storeNo);}
    @GetMapping("/stock-sync/mappings") public PageData<MappingVO> mappings(@PathVariable String storeNo,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest http){var c=scope.path(storeNo,http);return service.mappings(c.merchantNo(),storeNo,page,size);}
    @PutMapping("/stock-sync/mappings/{itemNo}") public MappingVO map(@PathVariable String storeNo,@PathVariable String itemNo,@RequestBody MappingCommand body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http){var c=scope.path(storeNo,http);return service.map(c.merchantNo(),storeNo,itemNo,SecurityUtils.currentUserNo(),body,key);}
    @GetMapping("/stock-sync/deliveries") public PageData<DeliveryVO> deliveries(@PathVariable String storeNo,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String itemNo,HttpServletRequest http){var c=scope.path(storeNo,http);return service.deliveries(c.merchantNo(),storeNo,page,size,status,itemNo);}
    @PostMapping("/stock-sync/deliveries/{syncNo}:retry") public DeliveryVO retry(@PathVariable String storeNo,@PathVariable String syncNo,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http){var c=scope.path(storeNo,http);return service.retry(c.merchantNo(),storeNo,syncNo,SecurityUtils.currentUserNo(),key);}
    public record ReconcileRequest(java.util.List<String> itemNos){}
    @PostMapping("/stock-sync:reconcile")
    public ai.neargo.shop.food.service.FoodStockReconciliationService.ReconciliationVO reconcile(
            @PathVariable String storeNo,@RequestBody ReconcileRequest body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest http){
        if(body==null)throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BAD_REQUEST);
        var c=scope.path(storeNo,http);return reconciliation.create(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),body.itemNos(),key);
    }
    @GetMapping("/stock-sync/reconciliations/{reconciliationNo}")
    public ai.neargo.shop.food.service.FoodStockReconciliationService.ReconciliationVO reconciliation(
            @PathVariable String storeNo,@PathVariable String reconciliationNo,@RequestParam(defaultValue="1")int page,
            @RequestParam(defaultValue="20")int size,HttpServletRequest http){var c=scope.path(storeNo,http);return reconciliation.get(c.merchantNo(),storeNo,reconciliationNo,page,size);}
}
