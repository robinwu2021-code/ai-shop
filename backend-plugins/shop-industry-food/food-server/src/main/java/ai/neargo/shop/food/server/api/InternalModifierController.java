package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal modifier adapters use the original service token and one authoritative ModifierService. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalModifierController {
    private final ModifierService service;
    public InternalModifierController(ModifierService service) { this.service = service; }

    @PostMapping(ModifierInternalApi.CREATE_PATH)
    public CreateModifierResultVO createModifier(@Valid @RequestBody ModifierInternalApi.CreateCommand c) {
        return service.createModifier(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.idempotencyKey());
    }

    @PostMapping(ModifierInternalApi.UPDATE_PATH)
    public ModifierMutationVO updateModifier(@Valid @RequestBody ModifierInternalApi.UpdateCommand c) {
        return service.updateModifier(c.entityNo(), c.storeNo(), c.modifierNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }

    @PostMapping(ModifierInternalApi.LIST_PATH)
    public PageData<ModifierConfigVO> listModifiers(@Valid @RequestBody ModifierInternalApi.Lookup c) {
        return service.listModifiers(c.entityNo(), c.storeNo(), c.query());
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
}
