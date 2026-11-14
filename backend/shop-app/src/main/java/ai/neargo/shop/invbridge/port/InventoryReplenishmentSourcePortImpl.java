package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Separate physical source: never consults or flips the business stock writer. */
@Component
@ConditionalOnProperty(name="shop.inventory.replenishment.source-system")
public class InventoryReplenishmentSourcePortImpl implements StockReplenishmentSourcePort {
    private final ObjectProvider<InventoryClient> clients;
    private final String sourceSystem;
    public InventoryReplenishmentSourcePortImpl(ObjectProvider<InventoryClient> clients,
            @Value("${shop.inventory.replenishment.source-system}") String sourceSystem){
        if(sourceSystem.isBlank()||sourceSystem.length()>32)throw new IllegalArgumentException("Stable replenishment source-system required");
        this.clients=clients;this.sourceSystem=sourceSystem;
    }
    private InventoryClient.Session session(Scope scope,String actor){
        var client=clients.getIfUnique();if(client==null)throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        if(!"MAIN".equals(scope.tenantNo()))throw BizException.of(ErrorCode.FORBIDDEN);
        return client.owner(scope.entityNo(),null,actor);
    }
    @Override public Checkpoint checkpoint(Scope s,String actor){
        try{var fact=session(s,actor).replenishmentCheckpoint();return new Checkpoint(sourceSystem,fact.ownerId(),fact.postingSequence());}
        catch(InventoryException e){throw InventoryErrors.toShop(e);}
    }
    @Override public Outbound outbound(Scope s,String actor,String no,int line){
        try{
            var fact=session(s,actor).replenishmentOutbound(no,line);
            if(!s.storeNo().equals(fact.targetStoreNo()))throw BizException.of(ErrorCode.FORBIDDEN);
            return new Outbound(sourceSystem,fact.ownerId(),fact.documentNo(),fact.lineNo(),fact.itemId(),fact.baseUnit(),fact.quantity(),fact.targetStoreNo(),fact.postingSequence());
        }catch(InventoryException e){throw InventoryErrors.toShop(e);}
    }
    @Override public Inbound inbound(Scope s,String actor,String no,int line){
        try{
            var fact=session(s,actor).replenishmentInbound(no,line);
            return new Inbound(sourceSystem,fact.ownerId(),fact.documentNo(),fact.lineNo(),fact.itemId(),fact.baseUnit(),fact.quantity(),fact.sourceRef());
        }catch(InventoryException e){throw InventoryErrors.toShop(e);}
    }
}
