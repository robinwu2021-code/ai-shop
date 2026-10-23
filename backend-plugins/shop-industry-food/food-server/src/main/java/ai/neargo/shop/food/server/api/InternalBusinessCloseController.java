package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.OpenCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.ReportQuery;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.PreviewCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.GetQuery;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.SignCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.AdjustCommand;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Original internal security chain authenticates service calls before scope revalidation. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalBusinessCloseController {
    private final BusinessCloseService service;
    public InternalBusinessCloseController(BusinessCloseService service) { this.service=service; }
    @PostMapping("/internal/food/business-close/open")
    public DayVO open(@Valid @RequestBody OpenCommand c) {
        return service.open(c.entity(),c.store(),c.actor(),c.date(),c.key());
    }
    @PostMapping("/internal/food/business-close/report")
    public ReportVO report(@Valid @RequestBody ReportQuery c) {
        return service.report(c.entity(),c.store(),c.actor(),c.request());
    }
    @PostMapping("/internal/food/business-close/preview")
    public CloseVO preview(@Valid @RequestBody PreviewCommand c) {
        return service.preview(c.entity(),c.store(),c.actor(),c.request(),c.key());
    }
    @PostMapping("/internal/food/business-close/get")
    public CloseVO get(@Valid @RequestBody GetQuery c) {
        return service.get(c.entity(),c.store(),c.actor(),c.closeNo());
    }
    @PostMapping("/internal/food/business-close/sign")
    public CloseVO sign(@Valid @RequestBody SignCommand c) {
        return service.sign(c.entity(),c.store(),c.actor(),c.request(),c.key());
    }
    @PostMapping("/internal/food/business-close/adjust")
    public OperationVO adjust(@Valid @RequestBody AdjustCommand c) {
        return service.adjust(c.entity(),c.store(),c.actor(),c.request(),c.key());
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
