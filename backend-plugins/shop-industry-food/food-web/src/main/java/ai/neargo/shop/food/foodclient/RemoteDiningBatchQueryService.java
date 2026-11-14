package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.spi.DiningBatchQueryInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

/** One FOOD call returns the local page; no per-batch remote order/payment enrichment is performed. */
public class RemoteDiningBatchQueryService implements DiningBatchQueryService {
    private final DiningBatchQueryInternalApi api;
    public RemoteDiningBatchQueryService(DiningBatchQueryInternalApi api) { this.api=api; }
    @Override public PageData<DiningBatchSummaryVO> list(String entity,String store,String actor,
            ListDiningBatchesQuery input) {
        var query=input==null?new ListDiningBatchesQuery(null,null,null,null,null,null,null):input;
        try {
            var result=ServiceCalls.call(ServiceName.FOOD,
                    () -> api.list(new DiningBatchQueryInternalApi.Listing(entity,store,actor,query)));
            int page=query.page()==null?1:query.page(),size=query.size()==null?20:query.size();
            if(result==null || result.records()==null || result.page()!=page || result.size()!=size
                    || result.total()<result.records().size() || result.records().size()>size
                    || result.records().stream().anyMatch(row -> row==null || !store.equals(row.storeNo())
                            || row.batchNo()==null || row.status()==null || row.version()<0)) {
                throw new IllegalStateException("Malformed FOOD batch source listing");
            }
            return result;
        } catch(ServiceCallException failure) {
            ErrorCode code=switch(failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if(code!=null) throw BizException.of(code); throw failure;
        }
    }
}
