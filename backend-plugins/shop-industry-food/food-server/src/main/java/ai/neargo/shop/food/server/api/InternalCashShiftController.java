package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.CashShiftService;
import ai.neargo.shop.food.spi.CashShiftInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 现金交班的服务间入口：先验内部令牌。 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalCashShiftController {
    private final CashShiftService service;
    private final String token;

    public InternalCashShiftController(CashShiftService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(CashShiftInternalApi.ROOT + "/submit")
    public ResponseEntity<?> submit(@RequestBody CashShiftInternalApi.Submit r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> service.submit(r.entity(), r.store(), r.actor(), r.request(), r.key()));
    }

    @PostMapping(CashShiftInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(@RequestBody CashShiftInternalApi.Reference r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> service.get(r.entity(), r.store(), r.shiftNo()));
    }
}
