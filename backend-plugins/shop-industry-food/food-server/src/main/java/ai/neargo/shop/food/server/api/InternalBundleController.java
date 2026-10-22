package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.spi.BundleInternalApi;
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
public class InternalBundleController {
    private final BundleService service;
    public InternalBundleController(BundleService service) { this.service = service; }

    @PostMapping(BundleInternalApi.CREATE_PATH)
    public CreateBundleResultVO createBundle(@Valid @RequestBody BundleInternalApi.CreateCommand c) {
        return service.createBundle(c.entityNo(), c.storeNo(), c.actorNo(), c.definition(), c.idempotencyKey());
    }
    @PostMapping(BundleInternalApi.REPLACE_PATH)
    public BundleMutationVO replaceDefinition(@Valid @RequestBody BundleInternalApi.UpdateCommand c) {
        return service.replaceDefinition(c.entityNo(), c.storeNo(), c.skuNo(), c.actorNo(),
                c.definition(), c.idempotencyKey());
    }
    @PostMapping(BundleInternalApi.GET_PATH)
    public GetBundleVO getBundle(@Valid @RequestBody BundleInternalApi.GetQuery c) {
        return service.getBundle(c.entityNo(), c.storeNo(), c.skuNo());
    }
    @PostMapping(BundleInternalApi.LIST_PATH)
    public PageData<BundleConfigVO> listBundles(@Valid @RequestBody BundleInternalApi.Lookup c) {
        return service.listBundles(c.entityNo(), c.storeNo(), c.query());
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
