package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Dining reception uses original platform permission and owner/store context. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizReservationController {
    private final ReservationService service;
    private final FoodBizStoreScope scope;
    public BizReservationController(ReservationService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/reservations")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public PageData<ReservationVO> listReservations(@PathVariable("storeNo") @NotBlank @Size(max=32) String store,
            @Valid @ModelAttribute ListReservationsQuery query,HttpServletRequest request) {
        var context=scope.path(store,request);return service.listReservations(context.merchantNo(),store,query);
    }
    @GetMapping("/biz/x/food/reservations/{reservationNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public DetailVO get(@PathVariable @NotBlank @Size(max=32) String reservationNo,HttpServletRequest request) {
        var c=scope.current(request); return service.get(c.merchantNo(),c.currentStoreNo(),reservationNo);
    }
    @PostMapping("/biz/x/food/stores/{storeNo}/reservations")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public DetailVO create(@PathVariable @NotBlank @Size(max=32) String storeNo,@Valid @RequestBody CreateReq body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.path(storeNo,request);
        return service.create(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),body,key);
    }
    @PostMapping("/biz/x/food/reservations/{reservationNo}:change")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public DetailVO change(@PathVariable @NotBlank @Size(max=32) String reservationNo,
            @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.change(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),reservationNo,body,key);
    }
    @PostMapping("/biz/x/food/reservations/{reservationNo}:cancel")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public DetailVO cancel(@PathVariable @NotBlank @Size(max=32) String reservationNo,
            @Valid @RequestBody TransitionReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.cancel(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),reservationNo,body,key);
    }
    @PostMapping("/biz/x/food/reservations/{reservationNo}:no-show")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public DetailVO noShow(@PathVariable @NotBlank @Size(max=32) String reservationNo,
            @Valid @RequestBody TransitionReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.noShow(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),reservationNo,body,key);
    }
    @PostMapping("/biz/x/food/reservations/{reservationNo}:arrive")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public DetailVO arrive(@PathVariable @NotBlank @Size(max=32) String reservationNo,
            @Valid @RequestBody ArriveReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.arrive(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),reservationNo,body,key);
    }
}
