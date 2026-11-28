package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.spi.FoodKdsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 门店设备与厨显工位的内部接口（TDD-餐饮域迁移新库 §2.8 D-5）。 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalFoodKdsController {
    private final FoodKdsService service;
    private final String token;

    public InternalFoodKdsController(
            FoodKdsService service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/register")
    public ResponseEntity<?> register(
            @RequestBody FoodKdsInternalApi.Register c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.register(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.key()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/status")
    public ResponseEntity<?> changeStatus(
            @RequestBody FoodKdsInternalApi.Status c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given,
                () -> service.changeStatus(c.entityNo(), c.storeNo(), c.actorNo(), c.deviceNo(), c.definition()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/stations")
    public ResponseEntity<?> assignStations(
            @RequestBody FoodKdsInternalApi.Stations c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given,
                () -> service.assignStations(c.entityNo(), c.storeNo(), c.actorNo(), c.deviceNo(), c.definition()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/list")
    public ResponseEntity<?> list(
            @RequestBody FoodKdsInternalApi.Store c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> service.list(c.entityNo(), c.storeNo()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/authorize")
    public ResponseEntity<?> authorize(
            @RequestBody FoodKdsInternalApi.Authorize c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.authorize(c.entityNo(), c.storeNo(), c.actorNo(), c.deviceNo()));
    }
}
