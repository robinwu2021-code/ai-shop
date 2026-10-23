package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbStation;
import ai.neargo.shop.food.mapper.FoodMappers.StationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Publication captures station identity and complete incoming SKU role/version definitions under store lock. */
public final class StationPublicationSource {
    private final StationMapper heads;
    private final SkuStationMapper routes;
    private final BooleanSupplier enabled;
    public StationPublicationSource(StationMapper heads,SkuStationMapper routes,BooleanSupplier enabled) {
        this.heads=heads; this.routes=routes; this.enabled=enabled;
    }
    public record Route(String skuNo,String taskRole,long routeVersion) { }
    public record Captured(StationVO definition,List<Route> routes,List<String> skuNos) { }
    public Captured capture(String entity,String store,String number,boolean lock) {
        if(!enabled.getAsBoolean()) throw BizException.of(ErrorCode.NOT_FOUND);
        if(lock && heads.lockStore(entity,store).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var q=Wrappers.<FnbStation>lambdaQuery().eq(FnbStation::getEntityNo,entity)
                .eq(FnbStation::getStoreNo,store).eq(FnbStation::getStationNo,number);
        if(lock) q.last("FOR UPDATE");
        var row=heads.selectOne(q);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        if(row.getVersion()==null || row.getVersion()<0 || !Boolean.TRUE.equals(row.getEnabled())
                || row.getName()==null || row.getName().isBlank() || row.getStationType()==null
                || !List.of("HOT","COLD","BAR","PACK").contains(row.getStationType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var incoming=routes.stationRoutes(entity,store,number).stream()
                .map(r -> new Route(r.getSkuNo(),r.getTaskRole(),r.getRouteVersion()))
                .sorted(Comparator.comparing(Route::skuNo).thenComparing(Route::taskRole)).toList();
        if(incoming.stream().anyMatch(r -> r.skuNo()==null || r.skuNo().isBlank() || r.routeVersion()<1
                || !List.of("MAKE","ASSEMBLE","PACK").contains(r.taskRole()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var view=new StationVO(number,row.getVersion(),"DRAFT",store,row.getName(),row.getStationType(),
                true,row.getPublishedRevisionNo());
        return new Captured(view,incoming,incoming.stream().map(Route::skuNo).distinct().toList());
    }
    public long currentVersion(String entity, String store, String number, boolean lock) {
        if (!enabled.getAsBoolean()) throw BizException.of(ErrorCode.NOT_FOUND);
        if (lock && heads.lockStore(entity, store).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var query = Wrappers.<FnbStation>lambdaQuery().eq(FnbStation::getEntityNo, entity)
                .eq(FnbStation::getStoreNo, store).eq(FnbStation::getStationNo, number);
        if (lock) query.last("FOR UPDATE");
        var row = heads.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!Boolean.TRUE.equals(row.getEnabled()) || row.getVersion() == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return row.getVersion();
    }
    /** Advances only the working CAS and effective publication pointer; historical snapshots remain immutable. */
    public void advance(String entity,String store,String number,long version,FnbConfigRevision revision) {
        var row=heads.selectOne(Wrappers.<FnbStation>lambdaQuery().eq(FnbStation::getEntityNo,entity)
                .eq(FnbStation::getStoreNo,store).eq(FnbStation::getStationNo,number));
        if(row==null) throw BizException.of(ErrorCode.CONFLICT);
        row.setVersion(version);
        if("EFFECTIVE".equals(revision.getStatus())) row.setPublishedRevisionNo(revision.getRevisionNo());
        if(heads.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
    }
}
