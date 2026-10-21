package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.ListCheckActionsQuery;
import ai.neargo.shop.food.dto.ListChecksQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Same check service contract for embedded and service-authenticated standalone deployments. */
public interface CheckInternalApi {
    String OPEN_PATH="/internal/food/checks/open";
    @PostExchange(OPEN_PATH) CheckResultVO open(@RequestBody OpenCommand command);
    String LIST_PATH="/internal/food/checks/list";
    @PostExchange(LIST_PATH) PageData<CheckVO> list(@RequestBody Lookup command);
    String GET_PATH="/internal/food/checks/get";
    @PostExchange(GET_PATH) CheckDetailVO get(@RequestBody Identity command);
    String SEATS_PATH="/internal/food/checks/seats";
    @PostExchange(SEATS_PATH) CheckResultVO seats(@RequestBody SeatsCommand command);
    String CLEAR_PATH="/internal/food/checks/clear";
    @PostExchange(CLEAR_PATH) OperationVO clear(@RequestBody ClearCommand command);
    String TRANSFER_PATH="/internal/food/checks/transfer";
    @PostExchange(TRANSFER_PATH) OperationResultVO transfer(@RequestBody TransferCommand command);
    String COMBINE_PATH="/internal/food/checks/combine-tables";
    @PostExchange(COMBINE_PATH) OperationResultVO combine(@RequestBody CombineCommand command);
    String SHARE_PATH="/internal/food/tables/share";
    @PostExchange(SHARE_PATH) CheckResultVO share(@RequestBody ShareCommand command);
    String OPERATION_PATH="/internal/food/table-operations/get";
    @PostExchange(OPERATION_PATH) OperationResultVO operation(@RequestBody OperationLookup command);
    String ACTIONS_PATH="/internal/food/checks/actions";
    @PostExchange(ACTIONS_PATH) PageData<ActionLogVO> actions(@RequestBody ActionsLookup command);
    record OpenCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull OpenCheckReq request) { }
    record Lookup(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@Valid ListChecksQuery query) { }
    record Identity(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo) { }
    record OperationLookup(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String operationNo) { }
    record ActionsLookup(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo,@Valid ListCheckActionsQuery query) { }
    record SeatsCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull SaveCheckSeatsReq request) { }
    record ClearCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull ClearCheckReq request) { }
    record TransferCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull TransferCheckReq request) { }
    record CombineCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String checkNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull CombineTablesReq request) { }
    record ShareCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,@NotBlank @Size(max=32) String tableNo,@NotBlank @Size(max=32) String actorNo,@NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull OpenSharedCheckReq request) { }
}
