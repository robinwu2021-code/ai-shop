package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.Mapping;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.*;
import java.math.BigDecimal;

public interface StockReplenishmentService {
    CommandState commandState(Scope scope,String action,String key);
    CommandState cancelCommand(Scope scope,String actor,String action,String key,String reason);
    Connection connection(Scope scope);
    Connection configure(Scope scope,String actor,String key,ConnectionChange change);
    Connection findConfigurationCommand(Scope scope,String key);
    View registerManual(Scope scope,String actor,String key,String documentNo,int lineNo,Mapping mapping,String movementNo);
    View adoptManual(Scope scope,String actor,String key,String no,String movementNo);
    Page list(Scope scope,long after,Long through,int size,boolean attentionOnly);
    Reconciliation reconcile(Scope scope,String actor,String no);
    View register(Scope scope,String actor,String key,String documentNo,int lineNo,Mapping mapping);
    View receive(Scope scope,String actor,String key,String no,BigDecimal target);
    View close(Scope scope,String actor,String key,String no,String reason);
    View dispatchReturn(Scope scope,String actor,String key,String no,BigDecimal target,String reason);
    View ackReturn(Scope scope,String actor,String key,String no,String documentNo,int lineNo);
    View get(Scope scope,String no);
    View findCommand(Scope scope,String action,String key);
}
