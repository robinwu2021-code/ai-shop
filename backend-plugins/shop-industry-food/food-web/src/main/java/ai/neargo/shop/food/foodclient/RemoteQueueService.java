package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.Query;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.spi.QueueInternalApi;
import ai.neargo.shop.food.spi.QueueInternalApi.IssueCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.ListQuery;
import ai.neargo.shop.food.spi.QueueInternalApi.Identity;
import ai.neargo.shop.food.spi.QueueInternalApi.CallNextCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.ChangeCommand;
import ai.neargo.shop.food.spi.QueueInternalApi.SeatCommand;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.time.LocalDate;
import java.util.function.Supplier;

/** FOOD host owns original-key seating recovery; no client adapter creates occupancy. */
public class RemoteQueueService implements QueueService {
    private final QueueInternalApi api;
    public RemoteQueueService(QueueInternalApi api) { this.api=api; }
    @Override public QueueVO issue(String entity,String store,String actor,IssueReq request,String key) {
        return call(() -> api.issue(new IssueCommand(entity,store,actor,request,key)));
    }
    @Override public PageData<QueueVO> list(String entity,String store,String actor,Query query) {
        return call(() -> api.list(new ListQuery(entity,store,actor,query)));
    }
    @Override public QueueVO get(String entity,String store,String actor,String number) {
        return call(() -> api.get(new Identity(entity,store,actor,number)));
    }
    @Override public QueueVO callNext(String entity,String store,String actor,LocalDate date,String key) {
        return call(() -> api.callNext(new CallNextCommand(entity,store,actor,date,key)));
    }
    @Override public QueueVO change(String entity,String store,String actor,String number,String action,ChangeReq request,String key) {
        return call(() -> api.change(new ChangeCommand(entity,store,actor,number,action,request,key)));
    }
    @Override public QueueVO seat(String entity,String store,String actor,String number,SeatQueueReq request) {
        return call(() -> api.seat(new SeatCommand(entity,store,actor,number,request)));
    }
    @Override public QueueVO recoverSeating(String entity,String store,String actor,String number) {
        return call(() -> api.recoverSeating(new Identity(entity,store,actor,number)));
    }

    private <T> T call(Supplier<T> operation) {
        try { return ServiceCalls.call(ServiceName.FOOD,operation); }
        catch(ServiceCallException failure) {
            ErrorCode code=switch(failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if(code!=null) throw BizException.of(code);
            throw failure;
        }
    }
}
