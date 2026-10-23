package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.spi.QueueInternalApi.IssueCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.ListQuery;
import ai.neargo.shop.food.spi.QueueInternalApi.Identity;
import ai.neargo.shop.food.spi.QueueInternalApi.CallNextCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.ChangeCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.SeatCommand;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated internal transport; source store predicates are rechecked by QueueService. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalQueueController {
    private final QueueService service;
    public InternalQueueController(QueueService service) { this.service=service; }
    @PostMapping("/internal/food/queue/issue")
    public QueueVO issue(@Valid @RequestBody IssueCommand c) {
        return service.issue(c.entity(),c.store(),c.actor(),c.request(),c.key());
    }
    @PostMapping("/internal/food/queue/list")
    public PageData<QueueVO> list(@Valid @RequestBody ListQuery c) {
        return service.list(c.entity(),c.store(),c.actor(),c.query());
    }
    @PostMapping("/internal/food/queue/get")
    public QueueVO get(@Valid @RequestBody Identity c) {
        return service.get(c.entity(),c.store(),c.actor(),c.ticketNo());
    }
    @PostMapping("/internal/food/queue/callNext")
    public QueueVO callNext(@Valid @RequestBody CallNextCommand c) {
        return service.callNext(c.entity(),c.store(),c.actor(),c.businessDate(),c.key());
    }
    @PostMapping("/internal/food/queue/change")
    public QueueVO change(@Valid @RequestBody ChangeCommand c) {
        return service.change(c.entity(),c.store(),c.actor(),c.ticketNo(),c.action(),c.request(),c.key());
    }
    @PostMapping("/internal/food/queue/seat")
    public QueueVO seat(@Valid @RequestBody SeatCommand c) {
        return service.seat(c.entity(),c.store(),c.actor(),c.ticketNo(),c.request());
    }
    @PostMapping("/internal/food/queue/recoverSeating")
    public QueueVO recoverSeating(@Valid @RequestBody Identity c) {
        return service.recoverSeating(c.entity(),c.store(),c.actor(),c.ticketNo());
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
