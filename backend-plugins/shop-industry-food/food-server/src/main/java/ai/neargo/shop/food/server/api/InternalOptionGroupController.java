package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal option-group adapters use the original service token and one authoritative OptionGroupService. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalOptionGroupController {
    private final OptionGroupService service;
    public InternalOptionGroupController(OptionGroupService service) { this.service = service; }

    @PostMapping(OptionGroupInternalApi.CREATE_PATH)
    public CreateOptionGroupResultVO createOptionGroup(@Valid @RequestBody OptionGroupInternalApi.CreateCommand c) {
        return service.createOptionGroup(c.entityNo(), c.storeNo(), c.actorNo(), c.group(), c.idempotencyKey());
    }

    @PostMapping(OptionGroupInternalApi.LIST_PATH)
    public PageData<OptionGroupConfigVO> listOptionGroups(@Valid @RequestBody OptionGroupInternalApi.Lookup query) {
        return service.listOptionGroups(query.entityNo(), query.storeNo(), query.query());
    }

    @PostMapping(OptionGroupInternalApi.GET_PATH)
    public OptionGroupResultVO getOptionGroup(@Valid @RequestBody OptionGroupInternalApi.Identity query) {
        return service.getOptionGroup(query.entityNo(), query.storeNo(), query.configNo());
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
