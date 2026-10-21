package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal modifier adapters use the original service token and one authoritative ConfigRevisionService. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalConfigRevisionController {
    private final ConfigRevisionService service;
    public InternalConfigRevisionController(ConfigRevisionService service) { this.service = service; }

    @PostMapping(ConfigRevisionInternalApi.RECIPE_PATH)
    public PublishConfigResultVO publishRecipe(@Valid @RequestBody ConfigRevisionInternalApi.Command c) {
        return service.publishRecipe(c.entityNo(), c.storeNo(), c.configNo(), c.actorNo(),
                c.publication(), c.idempotencyKey());
    }

    @PostMapping(ConfigRevisionInternalApi.BUNDLE_PATH)
    public PublishConfigResultVO publishBundle(@Valid @RequestBody ConfigRevisionInternalApi.Command c) {
        return service.publishBundle(c.entityNo(), c.storeNo(), c.configNo(), c.actorNo(),
                c.publication(), c.idempotencyKey());
    }

    @PostMapping(ConfigRevisionInternalApi.MODIFIER_PATH)
    public PublishConfigResultVO publishModifier(@Valid @RequestBody ConfigRevisionInternalApi.Command c) {
        return service.publishModifier(c.entityNo(), c.storeNo(), c.configNo(), c.actorNo(),
                c.publication(), c.idempotencyKey());
    }
    @PostMapping(ConfigRevisionInternalApi.GROUP_PATH)
    public PublishConfigResultVO publishOptionGroup(@Valid @RequestBody ConfigRevisionInternalApi.Command c) {
        return service.publishOptionGroup(c.entityNo(), c.storeNo(), c.configNo(), c.actorNo(),
                c.publication(), c.idempotencyKey());
    }
    @PostMapping(ConfigRevisionInternalApi.LIST_PATH)
    public PageData<ConfigRevisionVO> listRevisions(@Valid @RequestBody ConfigRevisionInternalApi.Lookup c) {
        return service.listRevisions(c.entityNo(), c.storeNo(), c.query());
    }
    @PostMapping(ConfigRevisionInternalApi.GET_PATH)
    public ConfigRevisionContentVO getRevision(@Valid @RequestBody ConfigRevisionInternalApi.Identity c) {
        return service.getRevision(c.entityNo(), c.storeNo(), c.revisionNo());
    }
    @PostMapping(ConfigRevisionInternalApi.RESOLVE_PATH)
    public ConfigRevisionContentVO resolveRevision(@Valid @RequestBody ConfigRevisionInternalApi.Resolve c) {
        return service.resolveRevision(c.entityNo(), c.storeNo(), c.configKind(), c.configNo(), c.asOf());
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
