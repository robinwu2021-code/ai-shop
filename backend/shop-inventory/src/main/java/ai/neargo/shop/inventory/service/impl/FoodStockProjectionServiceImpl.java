package ai.neargo.shop.inventory.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.projection.StockProjectionContracts;
import ai.neargo.inventory.client.InventoryProjection;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Source mirror adapter: remote service only, with no inventory SQL or embedded fallback. */
@Service
@ConditionalOnInventory
public class FoodStockProjectionServiceImpl implements InventoryFoodStockProjectionPort {
    private final ObjectProvider<InventoryReservationClient> clients;
    private final ObjectMapper json;
    public FoodStockProjectionServiceImpl(ObjectProvider<InventoryReservationClient> clients,ObjectMapper json){
        this.clients=clients;this.json=json;
    }
    @Override public List<MappingResult> validateMappings(Scope s,List<Mapping> mappings){
        var request=new StockProjectionContracts.MappingQuery(scope(s),json.convertValue(mappings,
                new TypeReference<List<StockProjectionContracts.Mapping>>(){}));
        return json.convertValue(remote(s,p->p.mappings(request)),new TypeReference<List<MappingResult>>(){});
    }
    @Override public Receipt activate(Scope s,long generation,String key){
        return receipt(remote(s,p->p.activate(new StockProjectionContracts.SessionCommand(scope(s),generation,key))));
    }
    @Override public Receipt deactivate(Scope s,long generation,String key){
        return receipt(remote(s,p->p.deactivate(new StockProjectionContracts.SessionCommand(scope(s),generation,key))));
    }
    @Override public Receipt apply(Scope s,long generation,String event,String hash,Snapshot snapshot){
        var request=new StockProjectionContracts.Apply(scope(s),generation,event,hash,
                json.convertValue(snapshot,StockProjectionContracts.Snapshot.class));
        return receipt(remote(s,p->p.apply(request)));
    }
    @Override public Receipt queryReceipt(Scope s,String event,String hash){
        return receipt(remote(s,p->p.receipt(new StockProjectionContracts.Query(scope(s),event,hash))).receipt());
    }
    @Override public List<Snapshot> queryProjection(Scope s,List<String> items){
        return json.convertValue(remote(s,p->p.query(new StockProjectionContracts.Items(scope(s),items))),
                new TypeReference<List<Snapshot>>(){});
    }
    public StockProjectionContracts.ProjectionPage list(Scope s,int page,int size){
        return remote(s,p->p.list(scope(s),page,size));
    }
    public StockProjectionContracts.ProjectionView get(Scope s,String item){return remote(s,p->p.get(scope(s),item));}
    private Receipt receipt(StockProjectionContracts.Receipt r){return json.convertValue(r,Receipt.class);}
    private static StockProjectionContracts.Scope scope(Scope s){return new StockProjectionContracts.Scope(s.entityNo(),s.storeNo());}
    private <T>T remote(Scope s,Function<InventoryProjection,T> call){
        try {
            var client=clients.getIfAvailable();
            if(client==null)throw InventoryException.of(InventoryError.UNAVAILABLE,"projection_client_not_configured");
            return call.apply(client.projection(s.entityNo(),"FOOD_SYNC"));
        }catch(InventoryException e){throw InventoryErrors.toShop(e);}
    }
}
