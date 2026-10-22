package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.spi.CatalogInternalApi;
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
public class InternalCatalogController {
    private final CatalogService service;
    public InternalCatalogController(CatalogService service) { this.service = service; }

    @PostMapping(CatalogInternalApi.READ_PATH)
    public CatalogVO read(@Valid @RequestBody CatalogInternalApi.Query q) {
        return service.read(q.entityNo(), q.storeNo(), q.definition());
    }
    @PostMapping(CatalogInternalApi.PUBLIC_PATH)
    public CatalogVO publicCatalog(@Valid @RequestBody CatalogInternalApi.PublicQuery q) {
        return service.publicCatalog(q.storeNo(), q.fulfillment());
    }
    @PostMapping(CatalogInternalApi.GUEST_PATH)
    public CatalogVO guest(@Valid @RequestBody CatalogInternalApi.GuestQuery q) {
        return service.guest(q.storeNo(), q.sessionNo(), q.accessToken(), q.buyerNo());
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
