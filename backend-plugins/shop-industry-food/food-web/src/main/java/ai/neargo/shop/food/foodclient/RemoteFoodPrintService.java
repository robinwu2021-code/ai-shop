package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.spi.FoodPrintInternalApi;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;
import java.util.Set;

/** The gateway checks the typed frozen receipt instead of inventing an accepted print on remote failure. */
public class RemoteFoodPrintService implements FoodPrintService {
    private final FoodPrintInternalApi api;
    public RemoteFoodPrintService(FoodPrintInternalApi api) { this.api=api; }
    @Override public List<JobVO> enqueue(Scope scope,PrintDocumentReq request,String key) {
        try {
            var result=ServiceCalls.call(ServiceName.FOOD,
                    () -> api.enqueue(new FoodPrintInternalApi.Command(scope,request,key)));
            if(result==null || result.isEmpty() || result.stream().anyMatch(row -> row==null
                    || !scope.storeNo().equals(row.storeNo()) || !request.scene().equals(row.scene())
                    || row.document()==null || !request.sourceNo().equals(row.document().sourceRef())
                    || !request.scene().equals(row.document().scene()) || row.jobNo()==null
                    || row.renderedContent()==null || !Set.of("PENDING","SENT","SUCCESS","FAILED","UNKNOWN")
                    .contains(row.status()==null?"":row.status()))) throw BizException.of(ErrorCode.CONFLICT);
            return result;
        } catch(ServiceCallException failure) {
            ErrorCode business=switch(failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST; case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND; case 409 -> ErrorCode.CONFLICT; default -> null;
            };
            if(business!=null) throw BizException.of(business); throw failure;
        }
    }
}
