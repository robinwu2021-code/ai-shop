package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.OpenCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.ReportQuery;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.PreviewCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.GetQuery;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.SignCommand;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi.AdjustCommand;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.time.LocalDate;
import java.util.function.Supplier;

/** Same typed contract for monolith and standalone deployment. */
public class RemoteBusinessCloseService implements BusinessCloseService {
    private final BusinessCloseInternalApi api;
    public RemoteBusinessCloseService(BusinessCloseInternalApi api) { this.api=api; }
    @Override public DayVO open(String entity,String store,String actor,LocalDate date,String key) {
        return call(() -> api.open(new OpenCommand(entity,store,actor,date,key)));
    }
    @Override public ReportVO report(String entity,String store,String actor,PreviewReq request) {
        return call(() -> api.report(new ReportQuery(entity,store,actor,request)));
    }
    @Override public CloseVO preview(String entity,String store,String actor,PreviewReq request,String key) {
        return call(() -> api.preview(new PreviewCommand(entity,store,actor,request,key)));
    }
    @Override public CloseVO get(String entity,String store,String actor,String closeNo) {
        return call(() -> api.get(new GetQuery(entity,store,actor,closeNo)));
    }
    @Override public CloseVO sign(String entity,String store,String actor,SignReq request,String key) {
        return call(() -> api.sign(new SignCommand(entity,store,actor,request,key)));
    }
    @Override public OperationVO adjust(String entity,String store,String actor,AdjustmentReq request,String key) {
        return call(() -> api.adjust(new AdjustCommand(entity,store,actor,request,key)));
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
