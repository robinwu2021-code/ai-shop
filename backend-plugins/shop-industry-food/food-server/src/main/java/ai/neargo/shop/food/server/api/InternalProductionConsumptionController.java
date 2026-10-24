package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi.ListCommand;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi.Command;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Original internal service-token chain authenticates callers; service revalidates source event store. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalProductionConsumptionController {
    private final ProductionConsumptionService service;
    public InternalProductionConsumptionController(ProductionConsumptionService service) { this.service=service; }
    @PostMapping("/internal/food/production/consume")
    public ConsumptionVO consume(@Valid @RequestBody Command c) {
        return service.consume(c.entity(),c.store(),c.actor(),c.sourceEventNo());
    }
    @PostMapping("/internal/food/production/get")
    public ConsumptionVO get(@Valid @RequestBody Command c) {
        return service.get(c.entity(),c.store(),c.actor(),c.sourceEventNo());
    }
    @PostMapping("/internal/food/production/list")
    public PageData<ConsumptionVO> list(@Valid @RequestBody ListCommand c) {
        return service.list(c.entity(),c.store(),c.actor(),c.query());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case ENTITLEMENT_FEATURE_UNAVAILABLE -> 422;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
