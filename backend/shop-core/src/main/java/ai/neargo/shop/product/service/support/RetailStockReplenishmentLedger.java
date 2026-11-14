package ai.neargo.shop.product.service.support;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdStockReplenishmentMovement;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMovementMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.spi.product.LocalStockControlPort.QuantityScope;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.Target;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** One current target observation, one conditional counter delta, one immutable effect in the caller's transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class RetailStockReplenishmentLedger {
    private final RetailReplenishmentTargetPort targets;
    private final SkuMapper skus;
    private final StoreStockMapper stores;
    private final StockReplenishmentMovementMapper movements;
    private final ObjectMapper json;

    public RetailStockReplenishmentLedger(RetailReplenishmentTargetPort targets, SkuMapper skus,
            StoreStockMapper stores, StockReplenishmentMovementMapper movements, ObjectMapper json) {
        this.targets=targets; this.skus=skus; this.stores=stores; this.movements=movements; this.json=json;
    }

    public PrdStockReplenishmentMovement apply(Scope scope, String actor, String action, String key,
            String original, Target expected, int delta, String kind, String reason) {
        var current=targets.requireCurrent(scope,new RetailReplenishmentTargetPort.Mapping(expected.skuNo(),
                expected.baseUnit(),BigDecimal.valueOf(Math.abs((long)delta)),BigDecimal.ONE),expected);
        if(!current.ready())throw BizException.of(ErrorCode.CONFLICT);
        long next=(long)current.onHand()+delta;
        if(delta==0||next<current.reserved()||next>Integer.MAX_VALUE)throw BizException.of(ErrorCode.CONFLICT);
        var target=current.target();
        int changed=DataScopeContext.executeWithoutScope(()->target.quantityScope()==QuantityScope.STORE
                ?stores.changeLocalStock(scope.entityNo(),target.poolRef(),target.skuNo(),delta,current.onHand(),current.reserved(),actor)
                :skus.changeLocalStock(scope.entityNo(),target.skuNo(),target.market(),delta,current.onHand(),current.reserved(),actor));
        if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);
        var movement=new PrdStockReplenishmentMovement();
        movement.setTenantNo("MAIN");movement.setEntityNo(scope.entityNo());movement.setStoreNo(scope.storeNo());
        movement.setVersion(0L);movement.setDeleted(0);movement.setCreatedBy(actor);movement.setUpdatedBy(actor);
        movement.setCreatedAt(LocalDateTime.now());movement.setUpdatedAt(movement.getCreatedAt());
        movement.setMovementNo(BizKey.next("PSM"));movement.setOriginalNo(original);
        movement.setCommandAction(action);movement.setOperationKey(key);movement.setKind(kind);
        movement.setItemNo(target.skuNo());movement.setBaseUnit(target.baseUnit());
        movement.setTargetIdentity(target.identity());movement.setTargetSnapshot(json.writeValueAsString(target));
        movement.setDelta(delta);movement.setOnHandBefore(current.onHand());movement.setOnHandAfter((int)next);
        movement.setReservedBefore(current.reserved());movement.setReservedAfter(current.reserved());movement.setReason(reason);
        DataScopeContext.executeWithoutScope(()->movements.insert(movement));return movement;
    }
}
