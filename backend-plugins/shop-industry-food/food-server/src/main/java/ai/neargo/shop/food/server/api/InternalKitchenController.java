package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.spi.KitchenInternalApi;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Same fulfillment implementation serves embedded and standalone FOOD deployments. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalKitchenController {
    private final KitchenTaskService service;
    public InternalKitchenController(KitchenTaskService service) { this.service=service; }
    @PostMapping(KitchenInternalApi.FREEZEROUTES_PATH)
    public FrozenRoutes freezeRoutes(@Valid @RequestBody KitchenInternalApi.FreezeQuery c) {
        return service.freezeRoutes(c.entityNo(),c.storeNo(),c.skus());
    }
    @PostMapping(KitchenInternalApi.ACCEPTED_PATH)
    public TicketVO accepted(@Valid @RequestBody KitchenInternalApi.DispatchCommand c) {
        return service.accepted(c.entityNo(),c.storeNo(),c.batchNo(),c.dispatchKey());
    }
    @PostMapping(KitchenInternalApi.QUEUE_PATH)
    public List<TaskVO> queue(@Valid @RequestBody KitchenInternalApi.QueueQuery c) {
        return service.queue(c.entityNo(),c.storeNo(),c.stationNo(),c.batchNo());
    }
    @PostMapping(KitchenInternalApi.ACT_PATH)
    public EventVO act(@Valid @RequestBody KitchenInternalApi.TaskCommand c) {
        return service.act(c.entityNo(),c.storeNo(),c.actorNo(),c.taskNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(KitchenInternalApi.REMAKE_PATH)
    public TicketVO remake(@Valid @RequestBody KitchenInternalApi.RemakeCommand c) {
        return service.remake(c.entityNo(),c.storeNo(),c.actorNo(),c.taskNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(KitchenInternalApi.EVENTS_PATH)
    public List<EventVO> events(@Valid @RequestBody KitchenInternalApi.TaskQuery c) {
        return service.events(c.entityNo(),c.storeNo(),c.taskNo());
    }
    @PostMapping(KitchenInternalApi.CREATECOURSE_PATH)
    public CourseVO createCourse(@Valid @RequestBody KitchenInternalApi.CreateCourseCommand c) {
        return service.createCourse(c.entityNo(),c.storeNo(),c.actorNo(),c.checkNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(KitchenInternalApi.COURSES_PATH)
    public List<CourseVO> courses(@Valid @RequestBody KitchenInternalApi.CourseQuery c) {
        return service.courses(c.entityNo(),c.storeNo(),c.checkNo());
    }
    @PostMapping(KitchenInternalApi.ACTCOURSE_PATH)
    public CourseVO actCourse(@Valid @RequestBody KitchenInternalApi.CourseCommand c) {
        return service.actCourse(c.entityNo(),c.storeNo(),c.actorNo(),c.courseNo(),c.definition(),c.idempotencyKey());
    }
    @PostMapping(KitchenInternalApi.HANDOVER_PATH)
    public HandoverVO handover(@Valid @RequestBody KitchenInternalApi.HandoverCommand c) {
        return service.handover(c.entityNo(),c.storeNo(),c.actorNo(),c.batchNo(),c.definition(),c.idempotencyKey());
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
