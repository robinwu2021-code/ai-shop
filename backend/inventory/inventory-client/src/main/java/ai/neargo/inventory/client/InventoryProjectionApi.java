package ai.neargo.inventory.client;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Receipt;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingQuery;
import ai.neargo.inventory.api.projection.StockProjectionContracts.SessionCommand;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Apply;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Query;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Items;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ReceiptLookup;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionView;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionPage;
import static ai.neargo.inventory.client.InventoryReservationApi.OWNER_REF;
import static ai.neargo.inventory.client.InventoryReservationApi.ACTOR;
import static ai.neargo.inventory.client.InventoryReservationApi.IDEMPOTENCY_KEY;
import java.util.List;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Independent source projection HTTP contract, with original command keys. */
@HttpExchange("/api/inventory/v1/stock-projections")
public interface InventoryProjectionApi {
    @PostExchange("/validate-mappings") List<MappingResult> mappings(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestBody MappingQuery request);
    @PostExchange("/activate") Receipt activate(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestHeader(IDEMPOTENCY_KEY) String key,@RequestBody SessionCommand request);
    @PostExchange("/deactivate") Receipt deactivate(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestHeader(IDEMPOTENCY_KEY) String key,@RequestBody SessionCommand request);
    @PostExchange("/apply") Receipt apply(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestHeader(IDEMPOTENCY_KEY) String key,@RequestBody Apply request);
    @PostExchange("/receipt") ReceiptLookup receipt(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestBody Query request);
    @PostExchange("/query") List<Snapshot> query(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestBody Items request);
    @GetExchange ProjectionPage list(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@RequestParam String entityNo,@RequestParam String storeNo,@RequestParam int page,@RequestParam int size);
    @GetExchange("/{itemNo}") ProjectionView get(@RequestHeader(OWNER_REF) String owner,@RequestHeader(ACTOR) String actor,@PathVariable String itemNo,@RequestParam String entityNo,@RequestParam String storeNo);
}
