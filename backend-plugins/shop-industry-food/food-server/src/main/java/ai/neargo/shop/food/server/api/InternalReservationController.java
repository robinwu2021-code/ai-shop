package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-token-only transport; no consumer/merchant session endpoints in the independent server. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalReservationController {
    private final ReservationService service;
    public InternalReservationController(ReservationService service) { this.service=service; }
    @PostMapping(ReservationInternalApi.LIST_PATH)
    public PageData<ReservationVO> list(@Valid @RequestBody ReservationInternalApi.Lookup c) {
        return service.listReservations(c.entityNo(),c.storeNo(),c.query());
    }

    /** Preserves business failures across the shared HTTP transport without returning query or row contents. */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status = switch (failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
    @PostMapping(ReservationInternalApi.GET_PATH)
    public DetailVO get(@Valid @RequestBody ReservationInternalApi.NumberLookup c) {
        return service.get(c.entityNo(),c.storeNo(),c.reservationNo());
    }
    @PostMapping(ReservationInternalApi.CREATE_PATH)
    public DetailVO create(@Valid @RequestBody ReservationInternalApi.CreateCommand c) {
        return service.create(c.entityNo(),c.storeNo(),c.actorNo(),c.request(),c.key());
    }
    @PostMapping(ReservationInternalApi.CHANGE_PATH)
    public DetailVO change(@Valid @RequestBody ReservationInternalApi.ChangeCommand c) {
        return service.change(c.entityNo(),c.storeNo(),c.actorNo(),c.reservationNo(),c.request(),c.key());
    }
    @PostMapping(ReservationInternalApi.CANCEL_PATH)
    public DetailVO cancel(@Valid @RequestBody ReservationInternalApi.TransitionCommand c) {
        return service.cancel(c.entityNo(),c.storeNo(),c.actorNo(),c.reservationNo(),c.request(),c.key());
    }
    @PostMapping(ReservationInternalApi.NO_SHOW_PATH)
    public DetailVO noShow(@Valid @RequestBody ReservationInternalApi.TransitionCommand c) {
        return service.noShow(c.entityNo(),c.storeNo(),c.actorNo(),c.reservationNo(),c.request(),c.key());
    }
    @PostMapping(ReservationInternalApi.ARRIVE_PATH)
    public DetailVO arrive(@Valid @RequestBody ReservationInternalApi.ArriveCommand c) {
        return service.arrive(c.entityNo(),c.storeNo(),c.actorNo(),c.reservationNo(),c.request(),c.key());
    }
}
