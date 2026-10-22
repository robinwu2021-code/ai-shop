package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.MenuCompositionResultVO;
import ai.neargo.shop.food.dto.MenuContextsResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.MenuScheduleResultVO;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.spi.MenuInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Uses the existing service-token chain and the same service as embedded restaurant operations. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalMenuController {
    private final MenuService service;
    public InternalMenuController(MenuService service) { this.service = service; }

    @PostMapping(MenuInternalApi.CREATE_PATH)
    public CreateMenuResultVO createMenu(@Valid @RequestBody MenuInternalApi.CreateCommand c) {
        return service.createMenu(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.UPDATE_PATH)
    public MenuResultVO updateMenu(@Valid @RequestBody MenuInternalApi.UpdateCommand c) {
        return service.updateMenu(c.entityNo(), c.storeNo(), c.menuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.SCHEDULE_PATH)
    public MenuScheduleResultVO replaceSchedule(@Valid @RequestBody MenuInternalApi.ScheduleCommand c) {
        return service.replaceSchedule(c.entityNo(), c.storeNo(), c.menuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.COMPOSITION_PATH)
    public MenuCompositionResultVO replaceComposition(@Valid @RequestBody MenuInternalApi.CompositionCommand c) {
        return service.replaceComposition(c.entityNo(), c.storeNo(), c.menuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.CONTEXTS_PATH)
    public MenuContextsResultVO replaceContexts(@Valid @RequestBody MenuInternalApi.ContextsCommand c) {
        return service.replaceContexts(c.entityNo(), c.storeNo(), c.menuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.DISABLE_PATH)
    public MenuResultVO disableMenu(@Valid @RequestBody MenuInternalApi.DisableCommand c) {
        return service.disableMenu(c.entityNo(), c.storeNo(), c.menuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(MenuInternalApi.GET_PATH)
    public MenuConfigResultVO getMenu(@Valid @RequestBody MenuInternalApi.GetQuery c) {
        return service.getMenu(c.entityNo(), c.storeNo(), c.menuNo());
    }
    @PostMapping(MenuInternalApi.LIST_PATH)
    public PageData<MenuVO> listMenus(@Valid @RequestBody MenuInternalApi.Lookup c) {
        return service.listMenus(c.entityNo(), c.storeNo(), c.query());
    }

    /** Unexpected provider failures must not become a successful public API envelope. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> infrastructureFailure(RuntimeException failure) {
        return ResponseEntity.internalServerError().build();
    }

    /** Carries the existing business status through typed transport without leaking row contents. */
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
}
