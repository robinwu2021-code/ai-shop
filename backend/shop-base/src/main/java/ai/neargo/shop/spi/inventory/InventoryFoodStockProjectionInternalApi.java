package ai.neargo.shop.spi.inventory;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.*;
public interface InventoryFoodStockProjectionInternalApi {
    String BASE="/internal/inventory/food-stock-projection/";
    record SessionCommand(Scope scope,long generation,String operationKey){}
    record Apply(Scope scope,long generation,String eventNo,String payloadHash,Snapshot snapshot){}
    record Query(Scope scope,String eventNo,String payloadHash){}
    record ReceiptLookup(Receipt receipt){}
    record Items(Scope scope,List<String> itemNos){public Items{itemNos=List.copyOf(itemNos);}}
    record MappingQuery(Scope scope,List<Mapping> mappings) {public MappingQuery{mappings=List.copyOf(mappings);}}
    @PostExchange(BASE+"validate-mappings") List<MappingResult> validateMappings(@RequestBody MappingQuery request);
    @PostExchange(BASE+"activate") Receipt activate(@RequestBody SessionCommand request);
    @PostExchange(BASE+"deactivate") Receipt deactivate(@RequestBody SessionCommand request);
    @PostExchange(BASE+"apply") Receipt apply(@RequestBody Apply request);
    @PostExchange(BASE+"receipt") ReceiptLookup queryReceipt(@RequestBody Query request);
    @PostExchange(BASE+"query") List<Snapshot> queryProjection(@RequestBody Items request);
}
