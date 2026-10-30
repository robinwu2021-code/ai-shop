package ai.neargo.shop.food.api.biz;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.*;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.food.service.FoodReturnStockService.*;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("api") @ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
@RequestMapping("/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions")
@PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"') and @perm.canBiz('"+BizPerms.STOCK+"')")
public class BizFoodReturnStockController {
    private final FoodReturnStockService service;private final FoodBizStoreScope scope;
    public BizFoodReturnStockController(FoodReturnStockService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator){this.service=service;this.scope=new FoodBizStoreScope(stores,validator);}
    @PostMapping public DispositionVO apply(@PathVariable String storeNo,@PathVariable String returnNo,@RequestBody Apply input,
            @RequestHeader("Idempotency-Key")String key,HttpServletRequest http){var c=scope.path(storeNo,http);return service.apply(c.merchantNo(),storeNo,returnNo,SecurityUtils.currentUserNo(),input,key);}
    @GetMapping public PageData<DispositionVO> list(@PathVariable String storeNo,@PathVariable String returnNo,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest http){var c=scope.path(storeNo,http);return service.list(c.merchantNo(),storeNo,returnNo,page,size);}
    @PostMapping("/{operationNo}:retry") public DispositionVO retry(@PathVariable String storeNo,@PathVariable String returnNo,
            @PathVariable String operationNo,HttpServletRequest http){var c=scope.path(storeNo,http);return service.retry(c.merchantNo(),storeNo,returnNo,operationNo,SecurityUtils.currentUserNo());}
}
