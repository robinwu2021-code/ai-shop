package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelBindingInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Embedded and standalone hosts use the same account-verified channel-binding service. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalMenuChannelBindingController {
    private final MenuChannelBindingService service;
    public InternalMenuChannelBindingController(MenuChannelBindingService service) { this.service=service; }
    @PostMapping(MenuChannelBindingInternalApi.CREATE_PATH)
    public MenuChannelBindingVO createBinding(@Valid @RequestBody MenuChannelBindingInternalApi.CreateCommand c) {
        return service.createBinding(c.entityNo(),c.storeNo(),c.actorNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(MenuChannelBindingInternalApi.UPDATE_PATH)
    public MenuChannelBindingVO updateBinding(@Valid @RequestBody MenuChannelBindingInternalApi.UpdateCommand c) {
        return service.updateBinding(c.entityNo(),c.storeNo(),c.bindingNo(),c.actorNo(),
                c.definition(),c.idempotencyKey());
    }
    @PostMapping(MenuChannelBindingInternalApi.GET_PATH)
    public MenuChannelBindingVO getBinding(@Valid @RequestBody MenuChannelBindingInternalApi.GetQuery c) {
        return service.getBinding(c.entityNo(),c.storeNo(),c.bindingNo());
    }
    @PostMapping(MenuChannelBindingInternalApi.LIST_PATH)
    public PageData<MenuChannelBindingVO> listBindings(@Valid @RequestBody MenuChannelBindingInternalApi.Lookup c) {
        return service.listBindings(c.entityNo(),c.storeNo(),c.page(),c.size(),c.provider(),c.menuNo());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
