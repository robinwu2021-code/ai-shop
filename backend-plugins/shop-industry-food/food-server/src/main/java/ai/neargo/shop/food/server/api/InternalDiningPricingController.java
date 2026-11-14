package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.food.spi.DiningPricingInternalApi;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Original consumer credentials still apply after internal token authentication. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningPricingController {
    private final DiningPricingService service;
    public InternalDiningPricingController(DiningPricingService service) { this.service = service; }
    @PostMapping(DiningPricingInternalApi.ROOT + "/staff/quote")
    public QuoteVO staffQuote(@RequestBody DiningPricingInternalApi.StaffQuote r) {
        return service.staffQuote(r.entity(), r.store(), r.actor(), r.request(), r.key());
    }
    @PostMapping(DiningPricingInternalApi.ROOT + "/staff/get")
    public QuoteVO staffGet(@RequestBody DiningPricingInternalApi.StaffGet r) {
        return service.staffGet(r.entity(), r.store(), r.actor(), r.cart(), r.version(), r.quote());
    }
    @PostMapping(DiningPricingInternalApi.ROOT + "/staff/confirm")
    public QuoteVO staffConfirm(@RequestBody DiningPricingInternalApi.StaffConfirm r) {
        return service.staffConfirm(r.entity(), r.store(), r.actor(), r.quote(), r.request());
    }
    @PostMapping(DiningPricingInternalApi.ROOT + "/customer/quote")
    public QuoteVO customerQuote(@RequestBody DiningPricingInternalApi.CustomerQuote r) {
        return service.customerQuote(r.store(), r.access(), r.request(), r.key());
    }
    @PostMapping(DiningPricingInternalApi.ROOT + "/customer/get")
    public QuoteVO customerGet(@RequestBody DiningPricingInternalApi.CustomerGet r) {
        return service.customerGet(r.access(), r.cart(), r.version(), r.quote());
    }
    @PostMapping(DiningPricingInternalApi.ROOT + "/customer/confirm")
    public QuoteVO customerConfirm(@RequestBody DiningPricingInternalApi.CustomerConfirm r) {
        return service.customerConfirm(r.access(), r.quote(), r.request());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status = switch (failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED -> 503;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
