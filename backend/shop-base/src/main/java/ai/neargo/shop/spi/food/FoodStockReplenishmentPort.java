package ai.neargo.shop.spi.food;

import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.LocalStockControlPort;

/** FOOD owns the physical receipt, permanent result and local quantity in one transaction. */
public interface FoodStockReplenishmentPort {
    /** Global source identity query for the trusted MAIN claim owner, including pre-registry originals. */
    View findSource(Scope scope, String system, String owner, String document, int line);
    default View registerClaimed(Scope scope, String actor, String key, Outbound source, Mapping mapping,
            StockReplenishmentClaim claim) {
        claim.requireFood(new LocalStockControlPort.Scope("MAIN",scope.entityNo(),scope.storeNo()),
                source,mapping.itemNo(),mapping.baseUnit(),mapping.factor());
        return register(scope,actor,key,source,mapping);
    }
    default View registerManualClaimed(Scope scope, String actor, String key, Outbound source, Mapping mapping,
            String movement, StockReplenishmentClaim claim) {
        claim.requireFood(new LocalStockControlPort.Scope("MAIN",scope.entityNo(),scope.storeNo()),
                source,mapping.itemNo(),mapping.baseUnit(),mapping.factor());
        return registerManual(scope,actor,key,source,mapping,movement);
    }
    record Mapping(@NotBlank @Size(max=32) String itemNo,@NotBlank @Size(max=16) String baseUnit,
                   @NotNull @DecimalMin("0.000001") @Digits(integer=18,fraction=6) BigDecimal factor) {}
    record View(String replenishmentNo,String sourceSystem,String sourceOwner,String sourceDocument,
                int sourceLine,String sourceItem,String sourceUnit,BigDecimal sourceQuantity,
                String itemNo,String baseUnit,BigDecimal factor,BigDecimal expectedQuantity,
                BigDecimal receivedQuantity,BigDecimal differenceQuantity,BigDecimal returnedQuantity,
                BigDecimal returnAcceptedQuantity,String status) {}
    record Connection(boolean enabled,long generation,long revision,String sourceSystem,String sourceOwner,long postingWatermark) {}
    record ConnectionChange(@NotNull Boolean enabled,@Min(0) long expectedVersion,@NotBlank @Size(max=256) String reason) {}
    record Page(List<View> items,Long nextCursor,long throughId){public Page{items=List.copyOf(items);}}
    record Reconciliation(View original,BigDecimal receivedLedgerQuantity,BigDecimal manualQuantity,
                          BigDecimal returnedLedgerQuantity,BigDecimal acceptedReturnQuantity,List<String> issues){
        public Reconciliation{issues=List.copyOf(issues);}
    }
    record CommandState(String status,View original,Connection connection) {}
    CommandState commandState(Scope scope,String action,String key);
    CommandState cancelCommand(Scope scope,String actor,String action,String key,String reason);
    Connection connection(Scope scope);
    Connection configure(Scope scope,String actor,String key,ConnectionChange change,Checkpoint source);
    Connection recoverConfiguration(Scope scope,String actor,String key,ConnectionChange change);
    Connection findConfigurationCommand(Scope scope,String key);
    View recoverRegistration(Scope scope,String actor,String key,String documentNo,int lineNo,Mapping mapping);
    View recoverManualRegistration(Scope scope,String actor,String key,String documentNo,int lineNo,Mapping mapping,
                                   String movementNo);
    View registerManual(Scope scope,String actor,String key,Outbound source,Mapping mapping,String movementNo);
    View adoptManual(Scope scope,String actor,String key,String no,String movementNo);
    Page list(Scope scope,long after,Long through,int size,boolean attentionOnly);
    Reconciliation reconcile(Scope scope,String no,Outbound source);
    View register(Scope scope,String actor,String key,Outbound source,Mapping mapping);
    View receive(Scope scope,String actor,String key,String no,BigDecimal cumulativeQuantity);
    View close(Scope scope,String actor,String key,String no,String reason);
    View dispatchReturn(Scope scope,String actor,String key,String no,BigDecimal cumulativeQuantity,String reason);
    View ackReturn(Scope scope,String actor,String key,String no,Inbound source);
    View get(Scope scope,String no);
    View findCommand(Scope scope,String action,String key);
}
