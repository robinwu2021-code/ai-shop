package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
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

/**
 * Keeps the InternalFoodKdsController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
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

    @PostMapping(FoodKdsInternalApi.ROOT + "/grant")
    public ResponseEntity<?> grant(
            @RequestBody FoodKdsInternalApi.Grant c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.grant(
                                c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.key()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/revoke")
    public ResponseEntity<?> revoke(
            @RequestBody FoodKdsInternalApi.Revoke c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.revoke(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.subscriptionNo(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/replay")
    public ResponseEntity<?> replay(
            @RequestBody FoodKdsInternalApi.Replay c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.replay(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.subscriptionNo(),
                                c.after(),
                                c.limit()));
    }

    @PostMapping(FoodKdsInternalApi.ROOT + "/append")
    public ResponseEntity<?> append(
            @RequestBody EventDraft c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    service.append(c);
                    return Boolean.TRUE;
                });
    }
}
