package ai.neargo.shop.food.channel.port;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi.*;
import java.util.List;
import java.util.function.Supplier;
/** The optional outbound mirror client never adapts native inventory reservation providers. */
public final class CoreFoodStockProjectionPortImpl implements InventoryFoodStockProjectionPort {
    private final Supplier<InventoryFoodStockProjectionInternalApi> api;
    public CoreFoodStockProjectionPortImpl(Supplier<InventoryFoodStockProjectionInternalApi> api){this.api=api;}
    @Override public List<MappingResult> validateMappings(Scope scope,List<Mapping> mappings){return api.get().validateMappings(new MappingQuery(scope,mappings));}
    @Override public Receipt activate(Scope s,long generation,String key){return api.get().activate(new SessionCommand(s,generation,key));}
    @Override public Receipt deactivate(Scope s,long generation,String key){return api.get().deactivate(new SessionCommand(s,generation,key));}
    @Override public Receipt apply(Scope s,long generation,String event,String hash,Snapshot snapshot){return api.get().apply(new Apply(s,generation,event,hash,snapshot));}
    @Override public Receipt queryReceipt(Scope s,String event,String hash){var result=api.get().queryReceipt(new Query(s,event,hash));if(result==null)throw new IllegalStateException("Missing projection lookup response");return result.receipt();}
    @Override public List<Snapshot> queryProjection(Scope s,List<String> items){return api.get().queryProjection(new Items(s,items));}
}
