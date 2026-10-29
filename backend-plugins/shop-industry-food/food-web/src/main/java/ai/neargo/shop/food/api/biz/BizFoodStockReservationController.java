package ai.neargo.shop.food.api.biz;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("api") @ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class BizFoodStockReservationController {
    private final FoodLocalStockPort stock;private final FoodBizStoreScope scope;
    public BizFoodStockReservationController(FoodLocalStockPort stock,StoreDirectoryPort stores,FoodRequestScopeValidator validator){this.stock=stock;scope=new FoodBizStoreScope(stores,validator);}
    @GetMapping("/biz/x/food/stores/{storeNo}/stock-reservations/{reservationNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
    public FoodLocalStockPort.ReservationView get(@PathVariable String storeNo,@PathVariable String reservationNo,HttpServletRequest http){var c=scope.path(storeNo,http);return stock.getReservation(new FoodLocalStockPort.Scope(c.merchantNo(),storeNo),reservationNo);}
}
