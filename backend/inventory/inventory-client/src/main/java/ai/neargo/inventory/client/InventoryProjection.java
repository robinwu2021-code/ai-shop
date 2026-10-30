package ai.neargo.inventory.client;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
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
import java.util.List;
import java.util.function.Supplier;

/** Uses the reservation client's existing credentials, error decoder and breaker; no hidden retries. */
public final class InventoryProjection {
    private final InventoryProjectionApi api;
    private final InventoryReservationClient client;
    private final String owner,actor;
    InventoryProjection(InventoryProjectionApi api,InventoryReservationClient client,String owner,String actor){
        this.api=api;this.client=client;this.owner=owner;this.actor=actor;
    }
    public List<MappingResult> mappings(MappingQuery r){return client.invoke(()->api.mappings(owner,actor,r));}
    public Receipt activate(SessionCommand r){return client.invoke(()->api.activate(owner,actor,r.operationKey(),r));}
    public Receipt deactivate(SessionCommand r){return client.invoke(()->api.deactivate(owner,actor,r.operationKey(),r));}
    public Receipt apply(Apply r){return client.invoke(()->api.apply(owner,actor,r.eventNo(),r));}
    public ReceiptLookup receipt(Query r){return client.invoke(()->api.receipt(owner,actor,r));}
    public List<Snapshot> query(Items r){return client.invoke(()->api.query(owner,actor,r));}
    public ProjectionPage list(Scope s,int page,int size){return client.invoke(()->api.list(owner,actor,s.entityNo(),s.storeNo(),page,size));}
    public ProjectionView get(Scope s,String item){return client.invoke(()->api.get(owner,actor,item,s.entityNo(),s.storeNo()));}
}
