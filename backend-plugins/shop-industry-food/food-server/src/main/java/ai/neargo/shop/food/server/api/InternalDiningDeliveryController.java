package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.food.spi.DiningDeliveryInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the InternalDiningDeliveryController protocol scoped to original restaurant facts and
 * lifecycle authority.
 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalDiningDeliveryController {
    private final DiningDeliveryService service;
    private final String token;

    public InternalDiningDeliveryController(
            DiningDeliveryService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(DiningDeliveryInternalApi.ROOT + "/save-staff")
    public ResponseEntity<?> saveStaff(
            @RequestBody DiningDeliveryInternalApi.StaffSave c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.saveStaff(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.cartNo(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(DiningDeliveryInternalApi.ROOT + "/save-customer")
    public ResponseEntity<?> saveCustomer(
            @RequestBody DiningDeliveryInternalApi.CustomerSave c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.saveCustomer(c.cartNo(), c.access(), c.definition(), c.key()));
    }

    @PostMapping(DiningDeliveryInternalApi.ROOT + "/get-staff")
    public ResponseEntity<?> getStaff(
            @RequestBody DiningDeliveryInternalApi.Source c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.getStaff(c.entityNo(), c.storeNo(), c.cartNo()));
    }

    @PostMapping(DiningDeliveryInternalApi.ROOT + "/get-customer")
    public ResponseEntity<?> getCustomer(
            @RequestBody DiningDeliveryInternalApi.CustomerQuery c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.getCustomer(c.cartNo(), c.access()));
    }

    @PostMapping(DiningDeliveryInternalApi.ROOT + "/source")
    public ResponseEntity<?> source(
            @RequestBody DiningDeliveryInternalApi.Source c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.get(c.entityNo(), c.storeNo(), c.cartNo()));
    }
}
