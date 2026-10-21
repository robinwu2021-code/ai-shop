package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRouteInput;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbSkuStation;
import ai.neargo.shop.food.entity.FnbStation;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StationMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.StationChanged;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Store policy locks serialize route edits with station enablement; remote SKU facts precede local transactions. */
public class StationServiceImpl implements StationService {
    private final StationMapper heads;
    private final SkuStationMapper routes;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final GoodsCatalogQueryPort catalog;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public StationServiceImpl(StationMapper heads, SkuStationMapper routes, ActionLogMapper actions,
            IdempotencyService idem, OutboxPublisher outbox, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort catalog) {
        this.heads=heads; this.routes=routes; this.actions=actions; this.idem=idem;
        this.outbox=outbox; this.json=json; this.catalog=catalog;
        write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public StationMutationVO createStation(String entity,String store,String actor,
            CreateStationReq input,String key) {
        context(entity,store,actor,key);
        if(input==null) bad();
        var req=new CreateStationReq(name(input.name()),type(input.stationType()));
        String scope=FoodCommandFingerprint.scope("CREATE_STATION",entity,store);
        String hash=FoodCommandFingerprint.hash("create-station:v1",json.writeValueAsString(req));
        return command(key,scope,actor,hash,StationMutationVO.class,() -> {
            lockStore(entity,store);
            var row=new FnbStation(); row.setEntityNo(entity); row.setStoreNo(store);
            row.setStationNo(BizKey.next("FSTA")); row.setName(req.name()); row.setStationType(req.stationType());
            row.setEnabled(true); row.setVersion(0L); heads.insert(row);
            record(entity,store,actor,key,"CREATE_STATION",row.getStationNo(),0,null,view(row));
            return new StationMutationVO(row.getStationNo(),0,"DRAFT");
        });
    }

    @Override public StationMutationVO updateStation(String entity,String store,String number,String actor,
            UpdateStationReq input,String key) {
        context(entity,store,actor,key); id(number);
        if(input==null || input.enabled()==null) bad();
        version(input.version());
        var req=new UpdateStationReq(input.version(),name(input.name()),type(input.stationType()),input.enabled());
        String scope=FoodCommandFingerprint.scope("UPDATE_STATION",entity,store);
        String hash=FoodCommandFingerprint.hash("update-station:v1",number,json.writeValueAsString(req));
        return command(key,scope,actor,hash,StationMutationVO.class,() -> {
            lockStore(entity,store); var row=head(entity,store,number,true);
            if(!req.version().equals(row.getVersion())) conflict();
            var before=view(row); row.setName(req.name()); row.setStationType(req.stationType());
            row.setEnabled(req.enabled());
            if(heads.updateById(row)!=1) conflict();
            record(entity,store,actor,key,"UPDATE_STATION",number,row.getVersion(),before,view(row));
            return new StationMutationVO(number,row.getVersion(),"DRAFT");
        });
    }

    @Override public GetStationVO getStation(String entity,String store,String number) {
        queryContext(entity,store); id(number);
        return DataScopeContext.executeWithoutScope(() ->
                read.execute(tx -> new GetStationVO(view(head(entity,store,number,false)))));
    }
    @Override public PageData<StationVO> listStations(String entity,String store,Integer inputPage,Integer inputSize) {
        queryContext(entity,store); int page=inputPage==null?1:inputPage; int size=inputSize==null?20:inputSize;
        if(page<1 || size<1 || size>100) bad();
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var result=heads.selectPage(new Page<FnbStation>(page,size),Wrappers.<FnbStation>lambdaQuery()
                    .eq(FnbStation::getEntityNo,entity).eq(FnbStation::getStoreNo,store)
                    .orderByAsc(FnbStation::getStationNo));
            return PageData.of(result.getRecords().stream().map(StationServiceImpl::view).toList(),
                    result.getTotal(),page,size);
        }));
    }
    @Override public OperationVO replaceRoutes(String entity,String store,String sku,String actor,
            ReplaceStationRoutesReq input,String key) {
        context(entity,store,actor,key); id(sku);
        var req=normalize(input);
        String scope=FoodCommandFingerprint.scope("REPLACE_STATION_ROUTES",entity,store);
        String hash=FoodCommandFingerprint.hash("station-routes:v1",sku,json.writeValueAsString(req));
        var replay=completed(key,scope,actor,hash,OperationVO.class);
        if(replay.isPresent()) return replay.get();
        var fact=catalog.getSku(entity,store,sku);
        if(fact==null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !sku.equals(fact.skuNo()) || fact.goodsNo()==null || fact.goodsNo().isBlank() || fact.version()<0) {
            throw new IllegalStateException("Invalid original SKU identity");
        }
        return command(key,scope,actor,hash,OperationVO.class,() -> {
            lockStore(entity,store);
            var old=routes.lockRoutes(entity,store,sku);
            long current=counter(old);
            if(current!=req.version()) conflict();
            for(var target:req.routes()) {
                var row=heads.selectOne(Wrappers.<FnbStation>lambdaQuery().eq(FnbStation::getEntityNo,entity)
                        .eq(FnbStation::getStoreNo,store).eq(FnbStation::getStationNo,target.stationNo())
                        .last("FOR UPDATE"));
                if(row==null || !Boolean.TRUE.equals(row.getEnabled())) bad();
            }
            // A never-configured empty relation is a durable idempotent no-op at version zero.
            long next=old.isEmpty() && req.routes().isEmpty()?0:current+1;
            var desired=new HashSet<StationRouteInput>(req.routes());
            for(var row:old) {
                var target=new StationRouteInput(row.getStationNo(),row.getTaskRole());
                boolean active=desired.remove(target);
                if(routes.retainRoute(entity,store,sku,row.getId(),next,active?0:1)!=1) conflict();
            }
            for(var target:desired) {
                var row=new FnbSkuStation(); row.setEntityNo(entity); row.setStoreNo(store); row.setSkuNo(sku);
                row.setStationNo(target.stationNo()); row.setTaskRole(target.taskRole()); row.setRouteVersion(next);
                row.setVersion(0L); routes.insert(row);
            }
            var after=new StationRoutesVO(sku,next,req.routes());
            String operation=record(entity,store,actor,key,"REPLACE_STATION_ROUTES",sku,next,
                    routeView(sku,old),after);
            return new OperationVO(operation,"SUCCEEDED",next,sku);
        });
    }
    @Override public StationRoutesVO getRoutes(String entity,String store,String sku) {
        queryContext(entity,store); id(sku);
        return DataScopeContext.executeWithoutScope(() ->
                read.execute(tx -> routeView(sku,routes.readRoutes(entity,store,sku))));
    }

    private <T> T command(String key,String scope,String actor,String hash,Class<T> type,
            Supplier<T> action) {
        var replay=completed(key,scope,actor,hash,type);
        if(replay.isPresent()) return replay.get();
        try { return DataScopeContext.executeWithoutScope(() ->
                write.execute(tx -> idem.execute(key,scope,actor,hash,type,action))); }
        catch(ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private <T> Optional<T> completed(String key,String scope,String actor,String hash,Class<T> type) {
        return read.execute(tx -> idem.findCompleted(key,scope,actor,hash,type));
    }
    private void lockStore(String entity,String store) {
        if(heads.lockStore(entity,store).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
    }
    private FnbStation head(String entity,String store,String number,boolean lock) {
        var q=Wrappers.<FnbStation>lambdaQuery().eq(FnbStation::getEntityNo,entity)
                .eq(FnbStation::getStoreNo,store).eq(FnbStation::getStationNo,number);
        if(lock) q.last("FOR UPDATE");
        var row=heads.selectOne(q); if(row==null) throw BizException.of(ErrorCode.NOT_FOUND); return row;
    }
    private static StationVO view(FnbStation row) {
        return new StationVO(row.getStationNo(),row.getVersion(),"DRAFT",row.getStoreNo(),row.getName(),
                row.getStationType(),Boolean.TRUE.equals(row.getEnabled()),row.getPublishedRevisionNo());
    }
    private static long counter(List<FnbSkuStation> rows) {
        long v=rows.stream().mapToLong(FnbSkuStation::getRouteVersion).max().orElse(0);
        if(v<0 || v==Long.MAX_VALUE) conflict(); return v;
    }
    private static StationRoutesVO routeView(String sku,List<FnbSkuStation> rows) {
        return new StationRoutesVO(sku,counter(rows),rows.stream()
                .filter(r -> Integer.valueOf(0).equals(r.getDeleted()))
                .map(r -> new StationRouteInput(r.getStationNo(),r.getTaskRole()))
                .sorted(Comparator.comparing(StationRouteInput::taskRole).thenComparing(StationRouteInput::stationNo))
                .toList());
    }
    private String record(String entity,String store,String actor,String key,String action,String number,
            long version,Object before,Object after) {
        var audit=new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("STATION"); audit.setObjectNo(number);
        audit.setAction(action); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action,entity,store),key));
        if(before!=null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(audit);
        outbox.publish(new StationChanged(entity,store,number,action,version,audit.getActionNo(),after),
                new PublishOptions("FOOD_STATION:"+store+":"+number,1));
        return audit.getActionNo();
    }
    public static ReplaceStationRoutesReq normalize(ReplaceStationRoutesReq req) {
        if(req==null || req.routes()==null || req.routes().size()>100) bad(); version(req.version());
        var seen=new HashSet<StationRouteInput>();
        for(var r:req.routes()) {
            if(r==null) bad(); id(r.stationNo());
            if(r.taskRole()==null || !Set.of("MAKE","ASSEMBLE","PACK").contains(r.taskRole()) || !seen.add(r)) bad();
        }
        return new ReplaceStationRoutesReq(req.version(),req.routes().stream()
                .sorted(Comparator.comparing(StationRouteInput::taskRole).thenComparing(StationRouteInput::stationNo))
                .toList());
    }
    private static String name(String value) {
        if(value==null || value.isBlank() || value.trim().length()>64) bad(); return value.trim();
    }
    private static String type(String value) {
        if(value==null || !Set.of("HOT","COLD","BAR","PACK").contains(value)) bad(); return value;
    }
    private static void version(Long version) { if(version==null || version<0 || version==Long.MAX_VALUE) bad(); }
    private static void id(String value) {
        if(value==null || value.isBlank() || value.length()>32 || !value.equals(value.trim())) bad();
    }
    private static void context(String entity,String store,String actor,String key) {
        queryContext(entity,store); id(actor); if(key==null || key.isBlank() || key.length()>128) bad();
    }
    private static void queryContext(String entity,String store) {
        id(entity); id(store);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Station operations cannot join an existing transaction");
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
