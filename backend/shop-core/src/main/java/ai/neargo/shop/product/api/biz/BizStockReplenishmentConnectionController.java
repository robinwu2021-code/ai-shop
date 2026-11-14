package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.StockReplenishmentService;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.Connection;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.ConnectionChange;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FOOD replenishment connection and caller context (split out of {@link BizStockReplenishmentController},
 * same paths, permission and scope rules); the request never controls the source owner, instance or actor.
 */
@RestController @Profile("api") @Validated
@RequestMapping("/biz/stock-control/replenishments")
@PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
public class BizStockReplenishmentConnectionController {
    public record Context(String entityNo,String storeNo,String actorNo,Connection connection) {}
    private final StockReplenishmentService service;
    private final LocalStockAccessPolicy access;
    public BizStockReplenishmentConnectionController(StockReplenishmentService service,LocalStockAccessPolicy access){this.service=service;this.access=access;}
    private Scope scope(String store,String expectedEntity){
        var actual=access.context(store);
        if(expectedEntity!=null&&!actual.entityNo().equals(expectedEntity))throw BizException.of(ErrorCode.FORBIDDEN);
        return actual;
    }
    @GetMapping("/context") public Context context(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,@RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity){
        var scope=scope(store,expectedEntity);return new Context(scope.entityNo(),scope.storeNo(),SecurityUtils.currentUserNo(),service.connection(scope));
    }
    @GetMapping("/connection") public Connection connection(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,@RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity){return service.connection(scope(store,expectedEntity));}
    @PostMapping("/connection") public Connection configure(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid ConnectionChange change){
        return service.configure(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,change);
    }
    @GetMapping("/connection/command") public Connection configurationCommand(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestParam @NotBlank @Size(max=128) String key){return service.findConfigurationCommand(scope(store,expectedEntity),key);}
}
