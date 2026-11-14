package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
import ai.neargo.shop.food.service.FoodStockItemService;
import ai.neargo.shop.food.service.FoodStockBalanceService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Management is store-authorized; no owner or actor can be supplied by the request body. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
@RequestMapping("/biz/x/food/stores/{storeNo}")
@PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
public class BizFoodStockController {
    private final FoodStockItemService items;
    private final FoodStockBalanceService balances;
    private final FoodBizStoreScope scope;
    public BizFoodStockController(FoodStockItemService items,FoodStockBalanceService balances,
            StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.items=items;this.balances=balances;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @PostMapping("/stock-items")
    public ItemVO create(@PathVariable String storeNo,@RequestBody CreateItem req,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return items.createItem(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/stock-items")
    public PageData<ItemVO> items(@PathVariable String storeNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String itemKind,@RequestParam(required=false) String status,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return items.listItems(c.merchantNo(),storeNo,page,size,keyword,itemKind,status);
    }
    @GetMapping("/stock-items/{itemNo}")
    public ItemVO item(@PathVariable String storeNo,@PathVariable String itemNo,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return items.getItem(c.merchantNo(),storeNo,itemNo);
    }
    @PutMapping("/stock-items/{itemNo}")
    public ItemVO update(@PathVariable String storeNo,@PathVariable String itemNo,@RequestBody UpdateItem req,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return items.updateItem(c.merchantNo(),storeNo,itemNo,SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/stock-balances")
    public BalanceVO initialize(@PathVariable String storeNo,@RequestBody Initialize req,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.initialize(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),req,key);
    }
    @PutMapping("/stock-balances/{itemNo}/policy")
    public BalanceVO policy(@PathVariable String storeNo,@PathVariable String itemNo,@RequestBody Policy req,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.changePolicy(c.merchantNo(),storeNo,itemNo,SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/stock-balances/{itemNo}:adjust")
    public BalanceVO adjust(@PathVariable String storeNo,@PathVariable String itemNo,@RequestBody Adjust req,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.adjust(c.merchantNo(),storeNo,itemNo,SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/stock-balances/{itemNo}")
    public BalanceVO balance(@PathVariable String storeNo,@PathVariable String itemNo,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.getBalance(c.merchantNo(),storeNo,itemNo);
    }
    @GetMapping("/stock-balances")
    public PageData<BalanceVO> balances(@PathVariable String storeNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.listBalances(c.merchantNo(),storeNo,page,size);
    }
    @GetMapping("/stock-movements")
    public PageData<MovementVO> movements(@PathVariable String storeNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String itemNo,
            @RequestParam(required=false) String action,
            @RequestParam(required=false) java.time.LocalDateTime from,
            @RequestParam(required=false) java.time.LocalDateTime to,HttpServletRequest http) {
        var c=scope.path(storeNo,http);return balances.listMovements(c.merchantNo(),storeNo,page,size,itemNo,action,from,to);
    }
}
