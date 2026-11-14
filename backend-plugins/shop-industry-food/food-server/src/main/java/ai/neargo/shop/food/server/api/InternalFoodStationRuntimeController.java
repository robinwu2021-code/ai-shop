package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoicePoll;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi;
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
 * Keeps the InternalFoodStationRuntimeController protocol scoped to original restaurant facts and
 * lifecycle authority.
 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalFoodStationRuntimeController {
    private final FoodStationRuntimeService service;
    private final String token;

    public InternalFoodStationRuntimeController(
            FoodStationRuntimeService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(FoodStationRuntimeInternalApi.ROOT + "/state")
    public ResponseEntity<?> state(
            @RequestBody FoodStationRuntimeInternalApi.Scope c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.state(c.entityNo(), c.storeNo(), c.stationNo()));
    }

    @PostMapping(FoodStationRuntimeInternalApi.ROOT + "/require-running")
    public ResponseEntity<?> running(
            @RequestBody FoodStationRuntimeInternalApi.Scope c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    service.requireRunning(c.entityNo(), c.storeNo(), c.stationNo());
                    return Boolean.TRUE;
                });
    }

    @PostMapping(FoodStationRuntimeInternalApi.ROOT + "/control")
    public ResponseEntity<?> control(
            @RequestBody FoodStationRuntimeInternalApi.Control c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.control(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.stationNo(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(FoodStationRuntimeInternalApi.ROOT + "/claim")
    public ResponseEntity<?> claim(
            @RequestBody FoodStationRuntimeInternalApi.Poll c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        new VoicePoll(
                                service.claim(
                                        c.entityNo(),
                                        c.storeNo(),
                                        c.actorNo(),
                                        c.subscriptionNo())));
    }

    @PostMapping(FoodStationRuntimeInternalApi.ROOT + "/acknowledge")
    public ResponseEntity<?> acknowledge(
            @RequestBody FoodStationRuntimeInternalApi.Receipt c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.acknowledge(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.subscriptionNo(),
                                c.definition()));
    }
}
