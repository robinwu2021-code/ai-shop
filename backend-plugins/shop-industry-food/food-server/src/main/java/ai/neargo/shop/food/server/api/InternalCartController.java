package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Shared internal token filter authenticates these typed draft commands before JSON is parsed. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalCartController {
    private final CheckCartService service;
    public InternalCartController(CheckCartService service) { this.service = service; }
    @PostMapping(CartInternalApi.CREATE_PATH)
    public CartResultVO create(@Valid @RequestBody CartInternalApi.CreateCommand c) {
        return service.createCart(c.entityNo(), c.storeNo(), c.actorNo(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.GET_PATH)
    public CartResultVO get(@Valid @RequestBody CartInternalApi.Identity c) {
        return service.getCart(c.entityNo(), c.storeNo(), c.cartNo());
    }
    @PostMapping(CartInternalApi.REPLACE_PATH)
    public CartVersionVO replaceLine(@Valid @RequestBody CartInternalApi.ReplaceLineCommand c) {
        return service.replaceLine(c.entityNo(), c.storeNo(), c.cartNo(), c.sourceLineRef(),
                c.actorNo(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.ARCHIVE_LINE_PATH)
    public CartVersionVO archiveLine(@Valid @RequestBody CartInternalApi.ArchiveLineCommand c) {
        return service.archiveLine(c.entityNo(), c.storeNo(), c.cartNo(), c.sourceLineRef(),
                c.actorNo(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.ARCHIVE_PATH)
    public OperationResultVO archive(@Valid @RequestBody CartInternalApi.ArchiveCommand c) {
        return service.archiveCart(c.entityNo(), c.storeNo(), c.cartNo(), c.actorNo(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.AUTHORIZE_PATH)
    public CartSessionGrantVO authorize(@Valid @RequestBody CartInternalApi.AuthorizeCommand c) {
        return service.authorizeSession(c.entityNo(), c.storeNo(), c.checkNo(),
                c.actorNo(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.CUSTOMER_CREATE_PATH)
    public CartResultVO customerCreate(@Valid @RequestBody CartInternalApi.CustomerCreateCommand c) {
        return service.customerCreateCart(c.storeNo(), c.access(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.CUSTOMER_GET_PATH)
    public CartResultVO customerGet(@Valid @RequestBody CartInternalApi.CustomerIdentity c) {
        return service.customerGetCart(c.cartNo(), c.access());
    }
    @PostMapping(CartInternalApi.CUSTOMER_REPLACE_PATH)
    public CartVersionVO customerReplaceLine(@Valid @RequestBody CartInternalApi.CustomerReplaceLineCommand c) {
        return service.customerReplaceLine(c.cartNo(), c.sourceLineRef(), c.access(), c.request(), c.idempotencyKey());
    }
    @PostMapping(CartInternalApi.CUSTOMER_ARCHIVE_LINE_PATH)
    public CartVersionVO customerArchiveLine(@Valid @RequestBody CartInternalApi.CustomerArchiveLineCommand c) {
        return service.customerArchiveLine(c.cartNo(), c.sourceLineRef(), c.access(), c.request(), c.idempotencyKey());
    }
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
