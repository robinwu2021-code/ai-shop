package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.spi.DiningIdentityInternalApi;
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
 * Keeps the InternalDiningIdentityController protocol scoped to original restaurant facts and
 * lifecycle authority.
 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalDiningIdentityController {
    private final DiningIdentityService service;
    private final String token;

    public InternalDiningIdentityController(
            DiningIdentityService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/device")
    public ResponseEntity<?> device(
            @RequestBody DiningIdentityInternalApi.DeviceQuery c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.device(c.entityNo(), c.storeNo(), c.deviceNo()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/register-device")
    public ResponseEntity<?> register(
            @RequestBody DiningIdentityInternalApi.Register c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.registerDevice(
                                c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.key()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/revoke-device")
    public ResponseEntity<?> revokeDevice(
            @RequestBody DiningIdentityInternalApi.DeviceRevoke c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.revokeDevice(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.deviceNo(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/device-session")
    public ResponseEntity<?> deviceSession(
            @RequestBody DiningIdentityInternalApi.DeviceSession c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.createDeviceSession(
                                c.entityNo(),
                                c.storeNo(),
                                c.deviceNo(),
                                c.credential(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/staff-party")
    public ResponseEntity<?> staffParty(
            @RequestBody DiningIdentityInternalApi.StaffParty c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.createStaffParty(
                                c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.key()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/revoke-party")
    public ResponseEntity<?> revokeParty(
            @RequestBody DiningIdentityInternalApi.PartyRevoke c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.revokeParty(
                                c.entityNo(),
                                c.storeNo(),
                                c.actorNo(),
                                c.sessionNo(),
                                c.definition(),
                                c.key()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/party")
    public ResponseEntity<?> party(
            @RequestBody DiningIdentityInternalApi.PartyQuery c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.party(c.entityNo(), c.storeNo(), c.sessionNo()));
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/validate-device-session")
    public ResponseEntity<?> validate(
            @RequestBody DiningIdentityInternalApi.SessionQuery c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    service.validateDeviceSession(c.sessionNo());
                    return Boolean.TRUE;
                });
    }

    @PostMapping(DiningIdentityInternalApi.ROOT + "/authenticate-device-session")
    public ResponseEntity<?> authenticateDeviceSession(
            @RequestBody DiningIdentityInternalApi.DeviceAccess q,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.authenticateDeviceSession(q.entityNo(), q.storeNo(), q.access()));
    }
}
