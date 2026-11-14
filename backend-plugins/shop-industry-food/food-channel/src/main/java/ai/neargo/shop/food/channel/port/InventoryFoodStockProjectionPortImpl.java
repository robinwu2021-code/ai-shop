package ai.neargo.shop.food.channel.port;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.projection.StockProjectionContracts;
import ai.neargo.inventory.client.InventoryProjection;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** FOOD edge translation only. Native transport, signing and breaker remain in inventory-client. */
public final class InventoryFoodStockProjectionPortImpl implements InventoryFoodStockProjectionPort {
    private final Supplier<InventoryReservationClient> client;
    private final ObjectMapper json;
    public InventoryFoodStockProjectionPortImpl(Supplier<InventoryReservationClient> client,ObjectMapper json){this.client=client;this.json=json;}
    @Override public List<MappingResult> validateMappings(Scope scope,List<Mapping> values){
        var request=new StockProjectionContracts.MappingQuery(scope(scope),json.convertValue(values,new TypeReference<List<StockProjectionContracts.Mapping>>(){}));
        return json.convertValue(call(scope,p->p.mappings(request)),new TypeReference<List<MappingResult>>(){});
    }
    @Override public Receipt activate(Scope scope,long generation,String key){return receipt(call(scope,p->p.activate(new StockProjectionContracts.SessionCommand(scope(scope),generation,key))));}
    @Override public Receipt deactivate(Scope scope,long generation,String key){return receipt(call(scope,p->p.deactivate(new StockProjectionContracts.SessionCommand(scope(scope),generation,key))));}
    @Override public Receipt apply(Scope scope,long generation,String key,String hash,Snapshot value){
        var request=new StockProjectionContracts.Apply(scope(scope),generation,key,hash,json.convertValue(value,StockProjectionContracts.Snapshot.class));
        return receipt(call(scope,p->p.apply(request)));
    }
    @Override public Receipt queryReceipt(Scope scope,String key,String hash){return receipt(call(scope,p->p.receipt(new StockProjectionContracts.Query(scope(scope),key,hash))).receipt());}
    @Override public List<Snapshot> queryProjection(Scope scope,List<String> items){return json.convertValue(call(scope,p->p.query(new StockProjectionContracts.Items(scope(scope),items))),new TypeReference<List<Snapshot>>(){});}
    private Receipt receipt(StockProjectionContracts.Receipt value){return json.convertValue(value,Receipt.class);}
    private StockProjectionContracts.Scope scope(Scope value){return new StockProjectionContracts.Scope(value.entityNo(),value.storeNo());}
    private <T>T call(Scope scope,Function<InventoryProjection,T> operation){
        try {
            var source=client.get();
            if(source==null)throw InventoryException.of(InventoryError.UNAVAILABLE,"projection_client_not_configured");
            return operation.apply(source.projection(scope.entityNo(),"FOOD_SYNC"));
        }catch(InventoryException failure){
            var error=switch(failure.error()){
                case BAD_REQUEST, PROFILE_INVALID, GATE_REF_MISSING -> ErrorCode.BAD_REQUEST;
                case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
                case FORBIDDEN, SYSTEM_NOT_SERVED, CAPABILITY_DISABLED -> ErrorCode.FORBIDDEN;
                case NOT_FOUND -> ErrorCode.NOT_FOUND;
                case CONFLICT, IDEMPOTENCY_CONFLICT, NOT_WRITER -> ErrorCode.CONFLICT;
                default -> ErrorCode.INTERNAL_ERROR;
            };
            var translated=BizException.of(error,failure.args());translated.initCause(failure);throw translated;
        }
    }
}
