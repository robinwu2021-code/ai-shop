package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.product.entity.PrdStockReplenishment;
import ai.neargo.shop.product.entity.PrdStockReplenishmentConnection;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentConnectionMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMapper;
import ai.neargo.shop.product.policy.StockReplenishmentWriterGuard;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.product.service.support.StockReplenishmentCommands;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockDispositionContract.ReservationOwner;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/** MAIN owns cross-industry source identities; target stock remains in its original ledger. */
@Service
@Validated
public class StockReplenishmentAdmissionServiceImpl implements StockReplenishmentAdmissionService {
    private final StockReplenishmentMapper originals;
    private final StockReplenishmentConnectionMapper connections;
    private final StockReplenishmentCommands commands;
    private final StockReplenishmentWriterGuard writers;
    private final RetailReplenishmentTargetPort targets;
    private final ObjectProvider<StockPort> stock;
    private final MerchantQueryPort merchants;
    private final ObjectProvider<FoodStockReplenishmentPort> food;
    private final ObjectProvider<FoodLocalStockPort> foodStock;
    private final ObjectMapper json;
    private final TransactionTemplate writes;

    public StockReplenishmentAdmissionServiceImpl(StockReplenishmentMapper originals,
            StockReplenishmentConnectionMapper connections, StockReplenishmentCommands commands,
            StockReplenishmentWriterGuard writers, RetailReplenishmentTargetPort targets,
            ObjectProvider<StockPort> stock,
            MerchantQueryPort merchants, ObjectProvider<FoodStockReplenishmentPort> food,
            ObjectProvider<FoodLocalStockPort> foodStock, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.originals=originals; this.connections=connections; this.commands=commands; this.writers=writers;
        this.targets=targets; this.merchants=merchants; this.food=food; this.foodStock=foodStock; this.json=json;
        this.stock=stock;
        writes=new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public StockReplenishmentClaim claimFood(Scope scope, String actor, Outbound source, Mapping mapping) {
        return claimFood(scope,actor,source,mapping,false);
    }

    @Override public StockReplenishmentClaim claimFood(Scope scope, String actor, Outbound source, Mapping mapping,
            boolean manualReceipt) {
        boundary(scope); sourceStore(scope,source);
        String hash=binding(scope,"FOOD_LOCAL",source,mapping);
        var known=findSource(source,false);
        if(known!=null){same(known,"FOOD_LOCAL",hash);return proof(known);}
        var legacy=legacy(scope,source);
        if(legacy==null){
            requireType(scope,"FOOD");
            if(!manualReceipt){
                var connection=food.getIfUnique().connection(local(scope));
                if(connection==null||!connection.enabled()||!source.sourceSystem().equals(connection.sourceSystem())
                        ||!source.sourceOwner().equals(connection.sourceOwner())
                        ||source.postingSequence()<=connection.postingWatermark())throw conflict();
            }
            var port=foodStock.getIfUnique();if(port==null)throw unavailable();
            var items=port.findAvailability(local(scope),List.of(new FoodLocalStockPort.ItemRef(mapping.itemNo(),mapping.baseUnit())));
            if(items==null||items.size()!=1||!"TRACKED".equals(items.getFirst().status())
                    ||!mapping.baseUnit().equals(items.getFirst().baseUnit()))throw conflict();
        }else{
            var old=new Mapping(legacy.itemNo(),legacy.baseUnit(),legacy.factor());
            var fact=new Outbound(legacy.sourceSystem(),legacy.sourceOwner(),legacy.sourceDocument(),legacy.sourceLine(),
                    legacy.sourceItem(),legacy.sourceUnit(),legacy.sourceQuantity(),scope.storeNo(),0);
            if(!hash.equals(binding(scope,"FOOD_LOCAL",fact,old)))throw conflict();
        }
        return write(()->{
            writers.lock(scope.entityNo());
            var current=findSource(source,true);
            if(current!=null){same(current,"FOOD_LOCAL",hash);return proof(current);}
            var row=newOriginal(scope,actor,"FOOD_LOCAL",source,mapping,hash);
            row.setStatus("EXTERNAL"); originals.insert(row);return proof(row);
        });
    }

    @Override public Original registerRetail(Scope scope, String actor, String key, Outbound source, Mapping mapping) {
        boundary(scope);sourceStore(scope,source);
        var old=recoverRegistration(scope,actor,key,source.documentNo(),source.lineNo(),mapping);
        if(old!=null)return old;
        String hash=binding(scope,"PLATFORM",source,mapping);
        var known=findSource(source,false);
        if(known!=null)same(known,"PLATFORM",hash);
        else if(legacy(scope,source)!=null)throw conflict();
        return commands.execute(scope,actor,"REGISTER",key,registration(actor,source.documentNo(),source.lineNo(),mapping),Original.class,writer->{
            var existing=findSource(source,true);
            if(existing!=null){same(existing,"PLATFORM",hash);return view(existing);}
            requireType(scope,"RETAIL");
            var connection=connectionRow(scope,true);
            if(connection==null||!Integer.valueOf(1).equals(connection.getEnabled())
                    ||!source.sourceSystem().equals(connection.getSourceSystem())
                    ||!source.sourceOwner().equals(connection.getSourceOwner())
                    ||source.postingSequence()<=connection.getPostingWatermark())throw conflict();
            if(!"REPLENISH".equals(writer.getMode()))throw conflict();
            var observed=targets.inspect(scope,new RetailReplenishmentTargetPort.Mapping(mapping.itemNo(),mapping.baseUnit(),
                    source.quantity(),mapping.factor()));
            if(!observed.ready())throw conflict();
            var row=newOriginal(scope,actor,"PLATFORM",source,mapping,hash);
            row.setTargetSnapshot(json.writeValueAsString(observed.target()));
            row.setAdmissionGeneration(connection.getGeneration());row.setStatus("PENDING");
            originals.insert(row);return view(row);
        });
    }

    @Override public Original recoverRegistration(Scope scope,String actor,String key,String document,int line,Mapping mapping) {
        boundary(scope);IdempotencyScope.requireKey(key);
        return commands.findCompleted(scope,"REGISTER",key,registration(actor,document,line,mapping),Original.class);
    }

    @Override public Original getRetail(Scope scope,String no) {
        boundary(scope);
        var row=DataScopeContext.executeWithoutScope(()->originals.selectOne(Wrappers.<PrdStockReplenishment>lambdaQuery()
                .eq(PrdStockReplenishment::getTenantNo,"MAIN").eq(PrdStockReplenishment::getEntityNo,scope.entityNo())
                .eq(PrdStockReplenishment::getStoreNo,scope.storeNo()).eq(PrdStockReplenishment::getOriginalNo,no)
                .eq(PrdStockReplenishment::getTargetOwner,"PLATFORM")));
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return view(row);
    }

    @Override public Connection configureRetail(Scope scope,String actor,String key,Change change,Checkpoint source) {
        boundary(scope);
        String hash=IdempotencyScope.requestHash(actor,"CONFIG",json.writeValueAsString(change));
        return commands.execute(scope,actor,"CONFIG",key,hash,Connection.class,writer->{
            var row=connectionRow(scope,true);
            if(row==null){row=new PrdStockReplenishmentConnection();audit(row,actor);row.setEntityNo(scope.entityNo());
                row.setStoreNo(scope.storeNo());row.setEnabled(0);row.setGeneration(0L);row.setPostingWatermark(0L);connections.insert(row);}
            if(row.getVersion()!=change.expectedVersion())throw conflict();
            if(change.enabled()){
                requireType(scope,"RETAIL");
                var executor=stock.getIfUnique();
                if(!(executor instanceof ReservationOwner owner)||!"PLATFORM".equals(owner.stockAuthority()))throw conflict();
                writers.takeLocal(writer,actor);
                if(!Integer.valueOf(1).equals(row.getEnabled())){
                    if(source==null||source.postingSequence()<row.getPostingWatermark())throw conflict();
                    if(row.getSourceSystem()!=null&&(!row.getSourceSystem().equals(source.sourceSystem())
                            ||!row.getSourceOwner().equals(source.sourceOwner())))throw conflict();
                    row.setSourceSystem(source.sourceSystem());row.setSourceOwner(source.sourceOwner());
                    row.setPostingWatermark(source.postingSequence());row.setGeneration(row.getGeneration()+1);
                }
            }
            row.setEnabled(change.enabled()?1:0);row.setUpdatedBy(actor);
            if(connections.updateById(row)!=1)throw conflict();return connectionView(row);
        });
    }

    @Override public Connection connection(Scope scope) {
        boundary(scope);var row=connectionRow(scope,false);
        return row==null?new Connection(false,0,0,null,null,0):connectionView(row);
    }

    private PrdStockReplenishment findSource(Outbound s,boolean lock) {
        return DataScopeContext.executeWithoutScope(()->originals.selectOne(Wrappers.<PrdStockReplenishment>lambdaQuery()
                .eq(PrdStockReplenishment::getTenantNo,"MAIN").eq(PrdStockReplenishment::getSourceSystem,s.sourceSystem())
                .eq(PrdStockReplenishment::getSourceOwner,s.sourceOwner()).eq(PrdStockReplenishment::getSourceDocument,s.documentNo())
                .eq(PrdStockReplenishment::getSourceLine,s.lineNo()).last(lock?"FOR UPDATE":"")));
    }

    private PrdStockReplenishmentConnection connectionRow(Scope s,boolean lock) {
        return DataScopeContext.executeWithoutScope(()->connections.selectOne(Wrappers.<PrdStockReplenishmentConnection>lambdaQuery()
                .eq(PrdStockReplenishmentConnection::getTenantNo,"MAIN").eq(PrdStockReplenishmentConnection::getEntityNo,s.entityNo())
                .eq(PrdStockReplenishmentConnection::getStoreNo,s.storeNo()).last(lock?"FOR UPDATE":"")));
    }

    private FoodStockReplenishmentPort.View legacy(Scope s,Outbound source) {
        var p=food.getIfUnique();if(p==null)throw unavailable();
        return p.findSource(local(s),source.sourceSystem(),source.sourceOwner(),source.documentNo(),source.lineNo());
    }

    private PrdStockReplenishment newOriginal(Scope s,String actor,String owner,Outbound source,Mapping mapping,String hash) {
        var r=new PrdStockReplenishment();audit(r,actor);r.setOriginalNo(BizKey.next("SRP"));r.setEntityNo(s.entityNo());
        r.setStoreNo(s.storeNo());r.setTargetOwner(owner);r.setSourceSystem(source.sourceSystem());r.setSourceOwner(source.sourceOwner());
        r.setSourceDocument(source.documentNo());r.setSourceLine(source.lineNo());r.setBindingHash(hash);
        r.setSourceSnapshot(json.writeValueAsString(source));r.setMappingSnapshot(json.writeValueAsString(mapping));
        r.setAdmissionGeneration(0L);r.setExpectedQuantity(expected(source,mapping));
        r.setReceivedQuantity(BigDecimal.ZERO);r.setDifferenceQuantity(BigDecimal.ZERO);r.setReturnedQuantity(BigDecimal.ZERO);
        r.setReturnAcceptedQuantity(BigDecimal.ZERO);return r;
    }

    private Original view(PrdStockReplenishment r) {
        return new Original(r.getOriginalNo(),r.getTargetOwner(),json.readValue(r.getSourceSnapshot(),Outbound.class),
                json.readValue(r.getMappingSnapshot(),Mapping.class),r.getTargetSnapshot()==null?null:
                json.readValue(r.getTargetSnapshot(),RetailReplenishmentTargetPort.Target.class),
                r.getExpectedQuantity(),r.getReceivedQuantity(),r.getStatus());
    }

    private static Connection connectionView(PrdStockReplenishmentConnection r) {
        return new Connection(Integer.valueOf(1).equals(r.getEnabled()),r.getGeneration(),r.getVersion(),
                r.getSourceSystem(),r.getSourceOwner(),r.getPostingWatermark());
    }
    private static String registration(String actor,String document,int line,Mapping m) {
        return IdempotencyScope.requestHash(actor,"REGISTER",document,Integer.toString(line),m.itemNo(),m.baseUnit(),
                m.factor().stripTrailingZeros().toPlainString());
    }
    private static String binding(Scope s,String owner,Outbound source,Mapping m) {
        return StockReplenishmentClaim.binding(s,owner,source,m.itemNo(),m.baseUnit(),m.factor());
    }
    private static BigDecimal expected(Outbound source,Mapping mapping) {
        BigDecimal quantity;
        try { quantity=source.quantity().multiply(mapping.factor()).setScale(6,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException invalid) { throw conflict(); }
        if(quantity.signum()<=0||quantity.precision()-quantity.scale()>18)throw conflict();
        return quantity;
    }
    private static void same(PrdStockReplenishment r,String owner,String hash) {
        if(!owner.equals(r.getTargetOwner())||!hash.equals(r.getBindingHash()))throw conflict();
    }
    private static StockReplenishmentClaim proof(PrdStockReplenishment r) {
        return new StockReplenishmentClaim(r.getOriginalNo(),r.getTargetOwner(),r.getBindingHash());
    }
    private void requireType(Scope s,String type) {
        var profile=merchants.storeProfileOf(s.storeNo()).orElseThrow(StockReplenishmentAdmissionServiceImpl::conflict);
        if(!s.entityNo().equals(profile.entityNo())||!type.equals(merchants.storeBusinessTypeOf(s.storeNo()).orElse(null)))throw conflict();
    }
    private static FoodLocalStockPort.Scope local(Scope s) { return new FoodLocalStockPort.Scope(s.entityNo(),s.storeNo()); }
    private static void sourceStore(Scope s,Outbound source) { if(!s.storeNo().equals(source.targetStoreNo()))throw conflict(); }
    private static void boundary(Scope s) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Admission owns its transaction");
        if(!"MAIN".equals(s.tenantNo()))throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private static void audit(BaseEntity r,String actor) {
        r.setTenantNo("MAIN");r.setVersion(0L);r.setDeleted(0);r.setCreatedBy(actor);r.setUpdatedBy(actor);
        r.setCreatedAt(LocalDateTime.now());r.setUpdatedAt(r.getCreatedAt());
    }
    private <T> T write(Supplier<T> work) {
        try{return DataScopeContext.executeWithoutScope(()->writes.execute(tx->work.get()));}
        catch(DuplicateKeyException|ConcurrencyFailureException race){throw conflict();}
    }
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
    private static BizException unavailable(){return BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);}
}
