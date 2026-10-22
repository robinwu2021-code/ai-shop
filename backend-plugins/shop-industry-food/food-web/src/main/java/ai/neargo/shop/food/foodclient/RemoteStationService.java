package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.spi.StationInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Typed standalone transport validates scope and versions before exposing management receipts. */
public class RemoteStationService implements StationService {
    private final StationInternalApi api;
    public RemoteStationService(StationInternalApi api) { this.api=api; }
    @Override public StationMutationVO createStation(String entity,String store,String actor,
            CreateStationReq req,String key) {
        var result=invoke(() -> api.createStation(new StationInternalApi.CreateCommand(entity,store,actor,key,req)));
        if(result==null || !id(result.configNo()) || result.version()!=0 || !"DRAFT".equals(result.status())) invalid();
        return result;
    }
    @Override public StationMutationVO updateStation(String entity,String store,String station,String actor,
            UpdateStationReq req,String key) {
        var result=invoke(() -> api.updateStation(
                new StationInternalApi.UpdateCommand(entity,store,station,actor,key,req)));
        if(result==null || !station.equals(result.configNo()) || req==null || req.version()==null
                || req.version()<0 || req.version()==Long.MAX_VALUE || result.version()!=req.version()+1
                || !"DRAFT".equals(result.status())) invalid();
        return result;
    }
    @Override public GetStationVO getStation(String entity,String store,String station) {
        var result=invoke(() -> api.getStation(new StationInternalApi.GetQuery(entity,store,station)));
        if(result==null || !valid(result.config(),store) || !station.equals(result.config().configNo())) invalid();
        return result;
    }
    @Override public PageData<StationVO> listStations(String entity,String store,Integer page,Integer size) {
        var result=invoke(() -> api.listStations(new StationInternalApi.Lookup(entity,store,page,size)));
        int expectedPage=page==null?1:page; int expectedSize=size==null?20:size;
        var seen=new HashSet<String>();
        if(result==null || result.records()==null || result.page()!=expectedPage || result.size()!=expectedSize
                || result.total()<result.records().size() || result.records().size()>expectedSize
                || result.records().stream().anyMatch(v -> !valid(v,store) || !seen.add(v.configNo()))) invalid();
        return result;
    }
    @Override public OperationVO replaceRoutes(String entity,String store,String sku,String actor,
            ReplaceStationRoutesReq req,String key) {
        var result=invoke(() -> api.replaceRoutes(
                new StationInternalApi.RoutesCommand(entity,store,sku,actor,key,req)));
        if(result==null || !id(result.operationNo()) || !sku.equals(result.resultRef())
                || !"SUCCEEDED".equals(result.status()) || req==null || req.version()==null
                || req.version()<0 || req.version()==Long.MAX_VALUE
                || result.version()!=req.version()+1 && !(req.version()==0 && req.routes()!=null
                && req.routes().isEmpty() && result.version()==0)) invalid();
        return result;
    }
    @Override public StationRoutesVO getRoutes(String entity,String store,String sku) {
        var result=invoke(() -> api.getRoutes(new StationInternalApi.GetQuery(entity,store,sku)));
        var seen=new HashSet<String>();
        if(result==null || !sku.equals(result.skuNo()) || result.version()<0 || result.routes()==null
                || result.routes().size()>100 || result.routes().stream().anyMatch(r -> r==null || !id(r.stationNo())
                || r.taskRole()==null || !Set.of("MAKE","ASSEMBLE","PACK").contains(r.taskRole())
                || !seen.add(r.stationNo()+":"+r.taskRole()))) invalid();
        return result;
    }
    private static boolean valid(StationVO v,String store) {
        return v!=null && id(v.configNo()) && v.version()>=0 && store.equals(v.storeNo())
                && "DRAFT".equals(v.status()) && v.name()!=null && !v.name().isBlank() && v.name().length()<=64
                && v.stationType()!=null && Set.of("HOT","COLD","BAR","PACK").contains(v.stationType())
                && (v.publishedRevisionNo()==null || id(v.publishedRevisionNo()));
    }
    private static boolean id(String v) { return v!=null && !v.isBlank() && v.length()<=32 && v.equals(v.trim()); }
    private static void invalid() { throw new IllegalStateException("Invalid station management receipt"); }
    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD,call); }
        catch(ServiceCallException failure) {
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
