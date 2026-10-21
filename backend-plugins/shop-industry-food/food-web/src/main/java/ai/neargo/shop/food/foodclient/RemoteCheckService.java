package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Original service discovery/token/timeout; no write retries or local fallback. */
public class RemoteCheckService implements CheckService {
    private final CheckInternalApi api;
    public RemoteCheckService(CheckInternalApi api) { this.api=api; }
    @Override public CheckResultVO openCheck(String entity,String store,String actor,OpenCheckReq req,String key) {
        return invoke(() -> api.open(new CheckInternalApi.OpenCommand(entity,store,actor,key,req)));
    }
    @Override public PageData<CheckVO> listChecks(String entity,String store,ListChecksQuery query) {
        return invoke(() -> api.list(new CheckInternalApi.Lookup(entity,store,query)));
    }
    @Override public CheckDetailVO getCheck(String entity,String store,String check) {
        return invoke(() -> api.get(new CheckInternalApi.Identity(entity,store,check)));
    }
    @Override public CheckResultVO saveSeats(String entity,String store,String check,String actor,SaveCheckSeatsReq req,String key) {
        return invoke(() -> api.seats(new CheckInternalApi.SeatsCommand(entity,store,check,actor,key,req)));
    }
    @Override public OperationVO clearCheck(String entity,String store,String check,String actor,ClearCheckReq req,String key) {
        return invoke(() -> api.clear(new CheckInternalApi.ClearCommand(entity,store,check,actor,key,req)));
    }
    @Override public OperationResultVO transferCheck(String entity,String store,String check,String actor,TransferCheckReq req,String key) {
        return invoke(() -> api.transfer(new CheckInternalApi.TransferCommand(entity,store,check,actor,key,req)));
    }
    @Override public OperationResultVO combineTables(String entity,String store,String check,String actor,CombineTablesReq req,String key) {
        return invoke(() -> api.combine(new CheckInternalApi.CombineCommand(entity,store,check,actor,key,req)));
    }
    @Override public CheckResultVO openSharedCheck(String entity,String store,String table,String actor,OpenSharedCheckReq req,String key) {
        return invoke(() -> api.share(new CheckInternalApi.ShareCommand(entity,store,table,actor,key,req)));
    }
    @Override public OperationResultVO getTableOperation(String entity,String store,String number) {
        return invoke(() -> api.operation(new CheckInternalApi.OperationLookup(entity,store,number)));
    }
    @Override public PageData<ActionLogVO> listActions(String entity,String store,String check,ListCheckActionsQuery query) {
        return invoke(() -> api.actions(new CheckInternalApi.ActionsLookup(entity,store,check,query)));
    }
    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
}
