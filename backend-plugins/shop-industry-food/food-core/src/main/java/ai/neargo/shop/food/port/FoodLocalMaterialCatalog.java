package ai.neargo.shop.food.port;

import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.policy.FoodMaterialFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Constructed inside FoodMaterialSource; not registered as a competing legacy SPI bean. */
public final class FoodLocalMaterialCatalog implements MaterialCatalogQueryPort {
    private final StockItemMapper items;private final StockBalanceMapper balances;private final TransactionTemplate read;
    public FoodLocalMaterialCatalog(StockItemMapper items,StockBalanceMapper balances,PlatformTransactionManager manager){this.items=items;this.balances=balances;read=new TransactionTemplate(manager);read.setReadOnly(true);}
    @Override public List<MaterialFact> getMaterials(String entity,List<String> numbers){MaterialCatalogQueryPort.validateLookup(entity,numbers);return read.execute(tx->items.selectList(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,entity).in(FnbStockItem::getItemNo,numbers).eq(FnbStockItem::getStatus,"ACTIVE")).stream().filter(i->i.getWeighed()!=null).map(this::fact).toList());}
    @Override public List<SkuMaterialFact> getSkuMaterials(String entity,String store,List<String> skus){MaterialCatalogQueryPort.validateLookup(entity,skus);if(store==null||store.isBlank()||store.length()>32)throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BAD_REQUEST);
        return read.execute(tx->{var result=new ArrayList<SkuMaterialFact>();for(var i:items.selectList(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN").eq(FnbStockItem::getEntityNo,entity).in(FnbStockItem::getSkuNo,skus).eq(FnbStockItem::getStatus,"ACTIVE"))){if(i.getWeighed()==null)continue;
            var b=balances.selectOne(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN").eq(FnbStockBalance::getEntityNo,entity).eq(FnbStockBalance::getStoreNo,store).eq(FnbStockBalance::getItemNo,i.getItemNo()));
            StockQuantity stock=null;
            if(b!=null&&"ACTIVE".equals(b.getStatus())&&"TRACKED".equals(b.getTrackingMode()))stock=new StockQuantity(b.getOnHand(),b.getReservedQty(),b.getOnHand().subtract(b.getReservedQty()),b.getVersion(),"KNOWN");
            else if(b!=null&&"ACTIVE".equals(b.getStatus())&&"UNTRACKED".equals(b.getTrackingMode()))stock=new StockQuantity(null,null,null,b.getVersion(),"UNTRACKED");
            result.add(new SkuMaterialFact(i.getSkuNo(),fact(i),stock));}return List.copyOf(result);});}
    private MaterialFact fact(FnbStockItem i){return new MaterialFact(i.getItemNo(),i.getEntityNo(),i.getName(),i.getBaseUnit(),i.getWeighed(),"FOOD_LOCAL","BASE_ONLY","EXACT_DECIMAL_6",FoodMaterialFacts.hash(i.getItemNo(),i.getEntityNo(),i.getName(),i.getBaseUnit(),i.getWeighed()));}
}
