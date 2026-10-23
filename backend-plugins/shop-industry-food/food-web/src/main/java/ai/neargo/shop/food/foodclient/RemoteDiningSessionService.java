package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** No retries or fallback: a secret-issuing request may have committed before transport failure. */
public class RemoteDiningSessionService implements DiningSessionService {
    private final DiningSessionInternalApi api;
    public RemoteDiningSessionService(DiningSessionInternalApi api) { this.api=api; }
    @Override public TableTokenVO rotateTableToken(String e,String s,String t,String a,RotateTableTokenReq r,String k) {
        return invoke(() -> api.rotate(new DiningSessionInternalApi.RotateCommand(e,s,t,a,k,r)));
    }
    @Override public OperationResultVO revokeTableToken(String e,String s,String t,String a,RevokeTableTokenReq r,String k) {
        return invoke(() -> api.revoke(new DiningSessionInternalApi.RevokeCommand(e,s,t,a,k,r)));
    }
    @Override public DiningSessionTableVO resolveTableToken(String token) {
        return invoke(() -> api.resolve(new DiningSessionInternalApi.ResolveCommand(token)));
    }
    @Override public DiningSessionVO startSession(String e,String s,String b,StartDiningSessionReq r,String k) {
        return invoke(() -> api.start(new DiningSessionInternalApi.StartCommand(e,s,b,k,r)));
    }
    @Override public OperationResultVO endSession(String session,String token,String buyer,String key) {
        return invoke(() -> api.end(new DiningSessionInternalApi.EndCommand(session,token,buyer,key)));
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
