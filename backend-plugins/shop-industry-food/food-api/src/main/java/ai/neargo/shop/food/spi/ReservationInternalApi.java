package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;

import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed reservation transport preserves local table authority without accepting payment claims. */
public interface ReservationInternalApi {
    String LIST_PATH="/internal/food/reservations/list";
    @PostExchange(LIST_PATH) PageData<ReservationVO> list(@RequestBody Lookup request);
    record Lookup(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @Valid @NotNull ListReservationsQuery query) { }
    String GET_PATH="/internal/food/reservations/get";
    @PostExchange(GET_PATH) DetailVO get(@RequestBody NumberLookup request);
    String CREATE_PATH="/internal/food/reservations/create";
    @PostExchange(CREATE_PATH) DetailVO create(@RequestBody CreateCommand request);
    String CHANGE_PATH="/internal/food/reservations/change";
    @PostExchange(CHANGE_PATH) DetailVO change(@RequestBody ChangeCommand request);
    String CANCEL_PATH="/internal/food/reservations/cancel";
    @PostExchange(CANCEL_PATH) DetailVO cancel(@RequestBody TransitionCommand request);
    String NO_SHOW_PATH="/internal/food/reservations/noShow";
    @PostExchange(NO_SHOW_PATH) DetailVO noShow(@RequestBody TransitionCommand request);
    String ARRIVE_PATH="/internal/food/reservations/arrive";
    @PostExchange(ARRIVE_PATH) DetailVO arrive(@RequestBody ArriveCommand request);
    record NumberLookup(@NotBlank @Size(max=32) String entityNo,
            @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String reservationNo) { }
    record CreateCommand(@NotBlank @Size(max=32) String entityNo,
            @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,
            @Valid @NotNull CreateReq request,
            @NotBlank @Size(max=128) String key) { }
    record ChangeCommand(@NotBlank @Size(max=32) String entityNo,
            @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=32) String reservationNo,
            @Valid @NotNull ChangeReq request,
            @NotBlank @Size(max=128) String key) { }
    record TransitionCommand(@NotBlank @Size(max=32) String entityNo,
            @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=32) String reservationNo,
            @Valid @NotNull TransitionReq request,
            @NotBlank @Size(max=128) String key) { }
    record ArriveCommand(@NotBlank @Size(max=32) String entityNo,
            @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=32) String reservationNo,
            @Valid @NotNull ArriveReq request,
            @NotBlank @Size(max=128) String key) { }
}
