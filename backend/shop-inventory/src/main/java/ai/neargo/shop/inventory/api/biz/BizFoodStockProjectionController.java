package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.*;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.FoodStockProjectionQueryService;
import ai.neargo.shop.inventory.service.FoodStockProjectionQueryService.ProjectionVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("api") @ConditionalOnInventory
public class BizFoodStockProjectionController {
    private final FoodStockProjectionQueryService service;
    public BizFoodStockProjectionController(FoodStockProjectionQueryService service){this.service=service;}
    @GetMapping("/biz/inventory/food-stock-projections")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
    public PageData<ProjectionVO> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String storeNo){var scope=scope(storeNo);return service.list(scope.merchantNo(),scope.currentStoreNo(),page,size);}
    @GetMapping("/biz/inventory/food-stock-projections/{sourceItemNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
    public ProjectionVO get(@PathVariable String sourceItemNo,@RequestParam(required=false)String storeNo){var scope=scope(storeNo);return service.get(scope.merchantNo(),scope.currentStoreNo(),sourceItemNo);}
    private BizContext scope(String requested){var c=BizContext.current();String store=BizContext.requireStoreNo();if(!c.hasStoreAccess(store)||requested!=null&&!requested.equals(store)||c.merchantNo()==null)throw BizException.of(ErrorCode.FORBIDDEN);return c;}
}
