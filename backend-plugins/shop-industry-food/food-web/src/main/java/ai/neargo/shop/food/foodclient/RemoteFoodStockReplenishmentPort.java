package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.*;
import java.math.BigDecimal;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;

public final class RemoteFoodStockReplenishmentPort implements FoodStockReplenishmentPort {
    private final FoodStockReplenishmentInternalApi api;
    public RemoteFoodStockReplenishmentPort(FoodStockReplenishmentInternalApi api){this.api=api;}
    @Override public View findSource(Scope s,String system,String owner,String document,int line){
        return FoodStockRemoteCalls.invoke(()->api.findSource(new FoodStockReplenishmentInternalApi.SourceQuery(s,system,owner,document,line))).result();
    }
    @Override public View registerClaimed(Scope s,String actor,String key,Outbound source,Mapping mapping,StockReplenishmentClaim claim){
        return FoodStockRemoteCalls.invoke(()->api.registerClaimed(new FoodStockReplenishmentInternalApi.RegisterClaimed(s,actor,key,source,mapping,claim,null)));
    }
    @Override public View registerManualClaimed(Scope s,String actor,String key,Outbound source,Mapping mapping,String movement,StockReplenishmentClaim claim){
        return FoodStockRemoteCalls.invoke(()->api.registerManualClaimed(new FoodStockReplenishmentInternalApi.RegisterClaimed(s,actor,key,source,mapping,claim,movement)));
    }
    @Override public CommandState commandState(Scope s,String action,String key){return FoodStockRemoteCalls.invoke(()->api.commandState(new FoodStockReplenishmentInternalApi.CommandQuery(s,action,key)));}
    @Override public CommandState cancelCommand(Scope s,String actor,String action,String key,String reason){return FoodStockRemoteCalls.invoke(()->api.cancelCommand(new FoodStockReplenishmentInternalApi.Cancel(s,actor,action,key,reason)));}
    @Override public Connection connection(Scope s){return FoodStockRemoteCalls.invoke(()->api.connection(new FoodStockReplenishmentInternalApi.ConfigQuery(s)));}
    @Override public Connection configure(Scope s,String actor,String key,ConnectionChange change,Checkpoint proof){return FoodStockRemoteCalls.invoke(()->api.configure(new FoodStockReplenishmentInternalApi.ConfigCommand(s,actor,key,change,proof)));}
    @Override public Connection recoverConfiguration(Scope s,String actor,String key,ConnectionChange change){return FoodStockRemoteCalls.invoke(()->api.recoverConfiguration(new FoodStockReplenishmentInternalApi.ConfigLookup(s,actor,key,change))).result();}
    @Override public Connection findConfigurationCommand(Scope s,String key){return FoodStockRemoteCalls.invoke(()->api.findConfigurationCommand(new FoodStockReplenishmentInternalApi.ConfigKey(s,key))).result();}
    @Override public View recoverRegistration(Scope s,String actor,String key,String doc,int line,Mapping mapping){return FoodStockRemoteCalls.invoke(()->api.recoverRegistration(new FoodStockReplenishmentInternalApi.RegistrationQuery(s,actor,key,doc,line,mapping))).result();}
    @Override public View recoverManualRegistration(Scope s,String actor,String key,String doc,int line,Mapping mapping,String movement){
        return FoodStockRemoteCalls.invoke(()->api.recoverRegistration(new FoodStockReplenishmentInternalApi.RegistrationQuery(s,actor,key,doc,line,mapping,movement))).result();
    }
    @Override public View registerManual(Scope s,String actor,String key,Outbound proof,Mapping mapping,String movement){return FoodStockRemoteCalls.invoke(()->api.registerManual(new FoodStockReplenishmentInternalApi.ManualRegister(s,actor,key,proof,mapping,movement)));}
    @Override public View adoptManual(Scope s,String actor,String key,String no,String movement){return FoodStockRemoteCalls.invoke(()->api.adoptManual(new FoodStockReplenishmentInternalApi.Manual(s,actor,key,no,movement)));}
    @Override public Page list(Scope s,long after,Long through,int size,boolean attentionOnly){return FoodStockRemoteCalls.invoke(()->api.list(new FoodStockReplenishmentInternalApi.ListQuery(s,after,through,size,attentionOnly)));}
    @Override public Reconciliation reconcile(Scope s,String no,Outbound proof){return FoodStockRemoteCalls.invoke(()->api.reconcile(new FoodStockReplenishmentInternalApi.Reconcile(s,no,proof)));}
    @Override public View register(Scope s,String actor,String key,Outbound source,Mapping mapping){return FoodStockRemoteCalls.invoke(()->api.register(new FoodStockReplenishmentInternalApi.Register(s,actor,key,source,mapping)));}
    @Override public View receive(Scope s,String actor,String key,String no,BigDecimal target){return FoodStockRemoteCalls.invoke(()->api.receive(new FoodStockReplenishmentInternalApi.Quantity(s,actor,key,no,target,null)));}
    @Override public View close(Scope s,String actor,String key,String no,String reason){return FoodStockRemoteCalls.invoke(()->api.close(new FoodStockReplenishmentInternalApi.Close(s,actor,key,no,reason)));}
    @Override public View dispatchReturn(Scope s,String actor,String key,String no,BigDecimal target,String reason){return FoodStockRemoteCalls.invoke(()->api.dispatchReturn(new FoodStockReplenishmentInternalApi.Quantity(s,actor,key,no,target,reason)));}
    @Override public View ackReturn(Scope s,String actor,String key,String no,Inbound source){return FoodStockRemoteCalls.invoke(()->api.ackReturn(new FoodStockReplenishmentInternalApi.Ack(s,actor,key,no,source)));}
    @Override public View get(Scope s,String no){return FoodStockRemoteCalls.invoke(()->api.get(new FoodStockReplenishmentInternalApi.Query(s,no)));}
    @Override public View findCommand(Scope s,String action,String key){return FoodStockRemoteCalls.invoke(()->api.findCommand(new FoodStockReplenishmentInternalApi.CommandQuery(s,action,key))).result();}
}
