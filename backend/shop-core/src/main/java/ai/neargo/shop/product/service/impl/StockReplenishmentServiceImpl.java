package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.service.StockReplenishmentService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.*;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import java.math.BigDecimal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class StockReplenishmentServiceImpl implements StockReplenishmentService {
    private final ObjectProvider<FoodStockReplenishmentPort> targets;
    private final ObjectProvider<StockReplenishmentSourcePort> sources;
    private final StockReplenishmentAdmissionService admissions;
    public StockReplenishmentServiceImpl(ObjectProvider<FoodStockReplenishmentPort> targets,ObjectProvider<StockReplenishmentSourcePort> sources,
            StockReplenishmentAdmissionService admissions){this.targets=targets;this.sources=sources;this.admissions=admissions;}
    private FoodStockReplenishmentPort target(){var p=targets.getIfUnique();if(p==null)throw unavailable();return p;}
    private StockReplenishmentSourcePort source(){var p=sources.getIfUnique();if(p==null)throw unavailable();return p;}
    private FoodLocalStockPort.Scope local(Scope s){if(!"MAIN".equals(s.tenantNo()))throw BizException.of(ErrorCode.FORBIDDEN);return new FoodLocalStockPort.Scope(s.entityNo(),s.storeNo());}
    @Override public CommandState commandState(Scope s,String action,String key){return target().commandState(local(s),action,key);}
    @Override public CommandState cancelCommand(Scope s,String actor,String action,String key,String reason){return target().cancelCommand(local(s),actor,action,key,reason);}
    @Override public Connection connection(Scope scope){return target().connection(local(scope));}
    @Override public Connection findConfigurationCommand(Scope scope,String key){return target().findConfigurationCommand(local(scope),key);}
    @Override public Connection configure(Scope scope,String actor,String key,ConnectionChange change){
        var p=target();var local=local(scope);var old=p.recoverConfiguration(local,actor,key,change);if(old!=null)return old;
        var current=p.connection(local);var proof=change.enabled()&&!current.enabled()?source().checkpoint(scope,actor):null;
        return p.configure(local,actor,key,change,proof);
    }
    @Override public View registerManual(Scope scope,String actor,String key,String doc,int line,Mapping mapping,String movement){
        var p=target();var s=local(scope);var old=p.recoverManualRegistration(s,actor,key,doc,line,mapping,movement);
        if(old!=null)return old;
        var fact=source().outbound(scope,actor,doc,line);
        var proof=admissions.claimFood(scope,actor,fact,new StockReplenishmentAdmissionService.Mapping(mapping.itemNo(),mapping.baseUnit(),mapping.factor()),true);
        return p.registerManualClaimed(s,actor,key,fact,mapping,movement,proof);
    }
    @Override public View adoptManual(Scope scope,String actor,String key,String no,String movement){return target().adoptManual(local(scope),actor,key,no,movement);}
    @Override public Page list(Scope scope,long after,Long through,int size,boolean attentionOnly){return target().list(local(scope),after,through,size,attentionOnly);}
    @Override public Reconciliation reconcile(Scope scope,String actor,String no){
        var p=target();var s=local(scope);var original=p.get(s,no);var proof=source().outbound(scope,actor,original.sourceDocument(),original.sourceLine());
        return p.reconcile(s,no,proof);
    }
    @Override public View register(Scope s,String actor,String key,String doc,int line,Mapping mapping){
        var p=target();var scope=local(s);var old=p.recoverRegistration(scope,actor,key,doc,line,mapping);if(old!=null)return old;var fact=source().outbound(s,actor,doc,line);
        if(!s.storeNo().equals(fact.targetStoreNo()))throw BizException.of(ErrorCode.FORBIDDEN);
        var proof=admissions.claimFood(s,actor,fact,new StockReplenishmentAdmissionService.Mapping(mapping.itemNo(),mapping.baseUnit(),mapping.factor()));
        return p.registerClaimed(scope,actor,key,fact,mapping,proof);
    }
    @Override public View receive(Scope s,String actor,String key,String no,BigDecimal target){return target().receive(local(s),actor,key,no,target);}
    @Override public View close(Scope s,String actor,String key,String no,String reason){return target().close(local(s),actor,key,no,reason);}
    @Override public View dispatchReturn(Scope s,String actor,String key,String no,BigDecimal target,String reason){return target().dispatchReturn(local(s),actor,key,no,target,reason);}
    @Override public View ackReturn(Scope s,String actor,String key,String no,String doc,int line){
        var p=target();var scope=local(s);p.get(scope,no);var fact=source().inbound(s,actor,doc,line);return p.ackReturn(scope,actor,key,no,fact);
    }
    @Override public View get(Scope s,String no){return target().get(local(s),no);}
    @Override public View findCommand(Scope s,String action,String key){return target().findCommand(local(s),action,key);}
    private static BizException unavailable(){return BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);}
}
