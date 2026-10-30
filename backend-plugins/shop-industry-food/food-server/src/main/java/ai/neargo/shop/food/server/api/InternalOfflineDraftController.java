package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.service.OfflineDraftService;
import ai.neargo.shop.food.spi.OfflineDraftInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Same owner behind original service-token and scope-signature filters. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(name = "shop.food.offline-drafts.enabled", havingValue = "true")
public class InternalOfflineDraftController {
    private final OfflineDraftService service;
    private final String token;

    public InternalOfflineDraftController(
            OfflineDraftService service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/challenge")
    public ResponseEntity<?> challenge(
            @RequestBody OfflineDraftInternalApi.ChallengeCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.challenge(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.request()));
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/enroll")
    public ResponseEntity<?> enroll(
            @RequestBody OfflineDraftInternalApi.EnrollCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.enroll(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.request()));
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/lease")
    public ResponseEntity<?> acquire(
            @RequestBody OfflineDraftInternalApi.LeaseCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.acquire(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.request()));
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/capture")
    public ResponseEntity<?> capture(
            @RequestBody OfflineDraftInternalApi.CaptureCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.capture(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.request()));
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/replay")
    public ResponseEntity<?> replay(
            @RequestBody OfflineDraftInternalApi.ReplayCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.replay(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.request()));
    }

    @PostMapping(OfflineDraftInternalApi.ROOT + "/state")
    public ResponseEntity<?> state(
            @RequestBody OfflineDraftInternalApi.StateCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.state(
                                command.entityNo(),
                                command.storeNo(),
                                command.deviceNo(),
                                command.access()));
    }
}
