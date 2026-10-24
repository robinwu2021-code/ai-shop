package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.PackingReq;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal FOOD protocol; caller scope is authenticated by the service-token boundary. */
public interface KitchenInternalApi {
    String PACKING_PATH="/internal/food/kitchen/packing";
    @PostExchange(PACKING_PATH) CustodyReceiptVO confirmPacking(@RequestBody PackingCommand command);
    String RECEIPTS_PATH="/internal/food/kitchen/custody-receipts";
    @PostExchange(RECEIPTS_PATH) List<CustodyReceiptVO> custodyReceipts(@RequestBody ReceiptQuery query);
    record PackingCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String batchNo,@NotBlank String idempotencyKey,@Valid @NotNull PackingReq definition) { }
    record ReceiptQuery(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String batchNo) { }
    String HANDOVER_PATH="/internal/food/kitchen/handover";
    @PostExchange(HANDOVER_PATH) HandoverVO handover(@RequestBody HandoverCommand command);
    record HandoverCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String batchNo,@NotBlank String idempotencyKey,@Valid @NotNull HandoverReq definition) { }
    String FREEZEROUTES_PATH="/internal/food/kitchen/freeze-routes";
    @PostExchange(FREEZEROUTES_PATH) FrozenRoutes freezeRoutes(@RequestBody FreezeQuery command);
    String ACCEPTED_PATH="/internal/food/kitchen/accepted";
    @PostExchange(ACCEPTED_PATH) TicketVO accepted(@RequestBody DispatchCommand command);
    String QUEUE_PATH="/internal/food/kitchen/queue";
    @PostExchange(QUEUE_PATH) List<TaskVO> queue(@RequestBody QueueQuery command);
    String ACT_PATH="/internal/food/kitchen/act";
    @PostExchange(ACT_PATH) EventVO act(@RequestBody TaskCommand command);
    String REMAKE_PATH="/internal/food/kitchen/remake";
    @PostExchange(REMAKE_PATH) TicketVO remake(@RequestBody RemakeCommand command);
    String EVENTS_PATH="/internal/food/kitchen/events";
    @PostExchange(EVENTS_PATH) List<EventVO> events(@RequestBody TaskQuery command);
    String CREATECOURSE_PATH="/internal/food/kitchen/create-course";
    @PostExchange(CREATECOURSE_PATH) CourseVO createCourse(@RequestBody CreateCourseCommand command);
    String COURSES_PATH="/internal/food/kitchen/courses";
    @PostExchange(COURSES_PATH) List<CourseVO> courses(@RequestBody CourseQuery command);
    String ACTCOURSE_PATH="/internal/food/kitchen/act-course";
    @PostExchange(ACTCOURSE_PATH) CourseVO actCourse(@RequestBody CourseCommand command);
    record FreezeQuery(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotNull List<@NotBlank String> skus) { }
    record DispatchCommand(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String batchNo,
            @NotBlank String dispatchKey) { }
    record QueueQuery(@NotBlank String entityNo,
            @NotBlank String storeNo,
            String stationNo,
            String batchNo) { }
    record TaskQuery(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String taskNo) { }
    record CourseQuery(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String checkNo) { }
    record TaskCommand(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String actorNo,
            @NotBlank String taskNo,
            @NotBlank String idempotencyKey,
            @Valid @NotNull TaskActionReq definition) { }
    record RemakeCommand(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String actorNo,
            @NotBlank String taskNo,
            @NotBlank String idempotencyKey,
            @Valid @NotNull RemakeReq definition) { }
    record CreateCourseCommand(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String actorNo,
            @NotBlank String checkNo,
            @NotBlank String idempotencyKey,
            @Valid @NotNull CourseReq definition) { }
    record CourseCommand(@NotBlank String entityNo,
            @NotBlank String storeNo,
            @NotBlank String actorNo,
            @NotBlank String courseNo,
            @NotBlank String idempotencyKey,
            @Valid @NotNull CourseActionReq definition) { }
}
