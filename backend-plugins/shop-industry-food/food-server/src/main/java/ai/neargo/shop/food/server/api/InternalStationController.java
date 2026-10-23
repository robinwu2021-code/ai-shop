package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.spi.StationInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Uses the same station service in standalone FOOD and embedded deployments. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalStationController {
    private final StationService service;
    public InternalStationController(StationService service) { this.service=service; }
    @PostMapping(StationInternalApi.CREATE_PATH)
    public StationMutationVO createStation(@Valid @RequestBody StationInternalApi.CreateCommand c) {
        return service.createStation(c.entityNo(),c.storeNo(),c.actorNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(StationInternalApi.UPDATE_PATH)
    public StationMutationVO updateStation(@Valid @RequestBody StationInternalApi.UpdateCommand c) {
        return service.updateStation(c.entityNo(),c.storeNo(),c.stationNo(),c.actorNo(),
                c.definition(),c.idempotencyKey());
    }
    @PostMapping(StationInternalApi.GET_PATH)
    public GetStationVO getStation(@Valid @RequestBody StationInternalApi.GetQuery c) {
        return service.getStation(c.entityNo(),c.storeNo(),c.configNo());
    }
    @PostMapping(StationInternalApi.LIST_PATH)
    public PageData<StationVO> listStations(@Valid @RequestBody StationInternalApi.Lookup c) {
        return service.listStations(c.entityNo(),c.storeNo(),c.page(),c.size());
    }
    @PostMapping(StationInternalApi.ROUTES_PATH)
    public OperationVO replaceRoutes(@Valid @RequestBody StationInternalApi.RoutesCommand c) {
        return service.replaceRoutes(c.entityNo(),c.storeNo(),c.skuNo(),c.actorNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(StationInternalApi.GET_ROUTES_PATH)
    public StationRoutesVO getRoutes(@Valid @RequestBody StationInternalApi.GetQuery c) {
        return service.getRoutes(c.entityNo(),c.storeNo(),c.configNo());
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
