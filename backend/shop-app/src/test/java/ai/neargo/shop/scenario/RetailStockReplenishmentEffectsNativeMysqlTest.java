package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.mapper.ProductMappers.*;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.RetailStockReplenishmentService.Adjustment;
import ai.neargo.shop.product.service.impl.MerchantGoodsServiceImpl;
import ai.neargo.shop.product.service.impl.RetailStockReplenishmentServiceImpl;
import ai.neargo.shop.product.service.support.RetailStockReplenishmentLedger;
import ai.neargo.shop.product.service.support.StockReplenishmentCommands;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.trade.order.service.create.OrderStockAllocator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL counters, transaction proxies and PLATFORM order/return path; source facts are test doubles. */
@EnabledIfEnvironmentVariable(named="I04B_NATIVE_MYSQL", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailStockReplenishmentEffectsNativeMysqlTest extends RetailStockReplenishmentNativeFixture {
    private String register() { enable();return admissions.registerRetail(S,"A","register",source("OUT",11),mapping()).originalNo(); }
    private Inbound inbound(String no,String doc,int quantity) {
        return new Inbound("INV","OWNER",doc,1,"SOURCE","EA",BigDecimal.valueOf(quantity),no);
    }

    @Test void cumulativeReceiptWritesOnlyDeltaAndReplaysFrozenResult() {
        String no=register();
        var first=effects.receive(S,"A","r18",no,18);
        assertThat(stock()).isEqualTo(35);
        effects.receive(S,"A","other18",no,18);
        effects.receive(S,"A","r20",no,20);
        assertThat(stock()).isEqualTo(37);assertThat(count("prd_stock_replenishment_movement")).isEqualTo(2);
        assertThat(effects.receive(S,"A","r18",no,18)).isEqualTo(first);
        assertThat(effects.get(S,no).original().receivedQuantity()).isEqualByComparingTo("20");
        assertThatThrownBy(()->effects.receive(S,"A","r18",no,19)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.receive(S,"A","lower",no,19)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.receive(S,"A","over",no,21)).isInstanceOf(BizException.class);
        assertThat(stock()).isEqualTo(37);
    }

    @Test void salesGiftsCancellationAndHandoffKeepOriginalLocalAccounting() {
        String no=register();effects.receive(S,"A","receipt",no,20);
        var allocator=new OrderStockAllocator(stockPort);
        var paid=List.of(new StockPort.SkuQty("SKU",4,"S","FUL",2));
        var gifts=List.of(new StockPort.SkuQty("SKU",6,"S","FUL",2));
        assertThat(allocator.reserve("ORDER",gifts,paid)).isTrue();
        assertThat(stock()).isEqualTo(37);assertThat(stockPort.sellable("SKU","S")).isEqualTo(31);
        stockPort.confirm("ORDER",2);assertThat(stock()).isEqualTo(37);
        stockPort.issue("FUL","ORDER");stockPort.issue("FUL","ORDER");
        assertThat(stock()).isEqualTo(31);
        allocator.reserve("CANCEL",List.of(new StockPort.SkuQty("SKU",3,"S","C",2)),List.of(new StockPort.SkuQty("SKU",3,"S","C",2)));
        stockPort.release("CANCEL");stockPort.release("CANCEL");
        assertThat(stockPort.sellable("SKU","S")).isEqualTo(31);
        assertThat(count("prd_stock_replenishment_movement")).isEqualTo(1);
        assertThat(effects.reconcile(S,no,source("OUT",11)).issues()).isEmpty();
        assertThat(stock()).isNotEqualTo(20); // Initial stock and sales belong to the same existing pool.
    }

    @Test void storePoolReceiptNeverTouchesEntityOrSiblingStock() {
        db.update("INSERT INTO prd_store_stock(store_no,entity_no,sku_no,stock,locked_stock,created_at,updated_at) VALUES('S','E','SKU',0,0,NOW(),NOW()),('OTHER','E','SKU',11,0,NOW(),NOW())");
        String no=register();effects.receive(S,"A","receipt",no,8);
        assertThat(stock()).isEqualTo(17);
        assertThat(db.queryForObject("SELECT stock FROM prd_store_stock WHERE store_no='S'",Integer.class)).isEqualTo(8);
        assertThat(db.queryForObject("SELECT stock FROM prd_store_stock WHERE store_no='OTHER'",Integer.class)).isEqualTo(11);
        assertThat(stockPort.lock("SALE",List.of(new StockPort.SkuQty("SKU",3,"S")))).isEmpty();stockPort.confirm("SALE");
        assertThat(db.queryForObject("SELECT stock FROM prd_store_stock WHERE store_no='S'",Integer.class)).isEqualTo(5);
        assertThat(stock()).isEqualTo(17);
    }

    @Test void differenceCloseAndWarehouseReturnHaveIndependentPhysicalAcknowledgment() {
        String no=register();effects.receive(S,"A","receive",no,8);
        var closed=effects.close(S,"A","close",no,"short delivery");
        assertThat(closed.differenceQuantity()).isEqualByComparingTo("12");
        assertThatThrownBy(()->effects.receive(S,"A","late",no,9)).isInstanceOf(BizException.class);
        effects.dispatchReturn(S,"A","return3",no,3,"damaged");
        effects.dispatchReturn(S,"A","return5",no,5,"damaged");
        effects.dispatchReturn(S,"A","repeat5",no,5,"damaged");assertThat(stock()).isEqualTo(20);
        assertThat(effects.reconcile(S,no,null).issues()).contains("SOURCE_UNVERIFIED","RECEIPT_DIFFERENCE","RETURN_PENDING");
        effects.ackReturn(S,"A","ack",no,inbound(no,"BACK",5));
        effects.ackReturn(S,"A","ack-again",no,inbound(no,"BACK",5));
        assertThat(stock()).isEqualTo(20);assertThat(count("prd_stock_replenishment_return")).isEqualTo(1);
        assertThat(effects.get(S,no).returnAcceptedQuantity()).isEqualByComparingTo("5");
        assertThat(effects.reconcile(S,no,source("OUT",11)).issues()).containsExactly("RECEIPT_DIFFERENCE");
        assertThatThrownBy(()->effects.ackReturn(S,"A","ahead",no,inbound(no,"AHEAD",1))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.ackReturn(S,"A","changed",no,inbound(no,"BACK",4))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.ackReturn(S,"A","wrong",no,inbound("OTHER","WRONG",1))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.dispatchReturn(S,"A","excess",no,9,"bad")).isInstanceOf(BizException.class);
    }

    @Test void manualReceiptRequiresRealAdjustmentAndDoesNotIncrementTwice() {
        String no=register();var target=effects.get(S,no).original().target();
        var adjustment=effects.adjust(S,"A","count",new Adjustment("SKU","EA",3,target,"actual count"));
        assertThat(stock()).isEqualTo(20);effects.close(S,"A","close",no,"short");
        effects.adoptManual(S,"A","manual",no,adjustment.movementNo());
        effects.adoptManual(S,"A","manual2",no,adjustment.movementNo());
        var result=effects.get(S,no);assertThat(stock()).isEqualTo(20);
        assertThat(result.original().status()).isEqualTo("CLOSED");assertThat(result.differenceQuantity()).isEqualByComparingTo("17");
        assertThat(count("prd_stock_replenishment_manual")).isEqualTo(1);
        var other=admissions.registerRetail(S,"A","register2",source("OUT2",12),mapping());
        assertThatThrownBy(()->effects.adoptManual(S,"A","duplicate",other.originalNo(),adjustment.movementNo())).isInstanceOf(BizException.class);
        var negative=effects.adjust(S,"A","negative",new Adjustment("SKU","EA",-1,target,"loss"));
        assertThatThrownBy(()->effects.adoptManual(S,"A","negative-proof",no,negative.movementNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.adoptManual(S,"A","fake",no,"old-absolute-stock-value")).isInstanceOf(BizException.class);
        assertThat(effects.reconcile(S,no,source("OUT",11)).manualQuantity()).isEqualByComparingTo("3");
    }

    @Test void boundsReservationsAndFrozenTargetRejectWithoutMovement() {
        String no=register();var t=effects.get(S,no).original().target();
        stockPort.lock("RESERVED",List.of(new StockPort.SkuQty("SKU",17,"S")));
        assertThatThrownBy(()->effects.adjust(S,"A","locked",new Adjustment("SKU","EA",-1,t,"loss"))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.adjust(S,"A","zero",new Adjustment("SKU","EA",0,t,"empty"))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->effects.adjust(S,"A","overflow",new Adjustment("SKU","EA",Integer.MAX_VALUE,t,"count"))).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment_movement")).isZero();
        db.update("UPDATE prd_sku SET sale_unit='KG'");
        assertThatThrownBy(()->effects.receive(S,"A","unit-changed",no,1)).isInstanceOf(BizException.class);
        assertThat(stock()).isEqualTo(17);
        assertThat(effects.reconcile(S,no,source("OUT",11)).issues()).contains("TARGET_UNAVAILABLE");
    }

    @Test void permanentCommandFailureRollsBackCounterHeadAndMovement() {
        String no=register();var receipt=spy(sessions.getMapper(StockReplenishmentCommandMapper.class));
        doThrow(new IllegalStateException("receipt insert failure")).when(receipt).insert(any(ai.neargo.shop.product.entity.PrdStockReplenishmentCommand.class));
        var json=JsonMapper.builder().build();var failing=new StockReplenishmentCommands(receipt,guard,json,manager);
        var ledger=proxy(new RetailStockReplenishmentLedger(target,sessions.getMapper(SkuMapper.class),sessions.getMapper(StoreStockMapper.class),
                sessions.getMapper(StockReplenishmentMovementMapper.class),json),RetailStockReplenishmentLedger.class);
        var service=new RetailStockReplenishmentServiceImpl(sessions.getMapper(StockReplenishmentMapper.class),sessions.getMapper(StockReplenishmentMovementMapper.class),
                sessions.getMapper(StockReplenishmentManualMapper.class),sessions.getMapper(StockReplenishmentReturnMapper.class),failing,ledger,target,json,manager);
        assertThatThrownBy(()->service.receive(S,"A","failure",no,5)).isInstanceOf(IllegalStateException.class);
        assertThat(stock()).isEqualTo(17);assertThat(count("prd_stock_replenishment_movement")).isZero();
        assertThat(effects.get(S,no).original().receivedQuantity()).isEqualByComparingTo("0");
        effects.receive(S,"A","failure",no,5);assertThat(stock()).isEqualTo(22);
    }

    @Test void concurrentSameKeyReceiptsHaveOnePhysicalEffect() throws Exception {
        String no=register();var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(6)) {
            var jobs=java.util.stream.IntStream.range(0,6).mapToObj(i->workers.submit(()->{
                start.await();return effects.receive(S,"A","concurrent",no,20);
            })).toList();start.countDown();
            for(var job:jobs)assertThat(job.get(15,TimeUnit.SECONDS).original().receivedQuantity()).isEqualByComparingTo("20");
        }
        assertThat(stock()).isEqualTo(37);assertThat(count("prd_stock_replenishment_movement")).isEqualTo(1);
    }

    @Test void customerSaleableAndUnsaleableReturnsRemainDistinctFromWarehouseReturn() {
        String no=register();effects.receive(S,"A","receipt",no,5);
        stockPort.lock("SALE",List.of(new StockPort.SkuQty("SKU",3,"S")));stockPort.confirm("SALE");
        assertThat(stock()).isEqualTo(19);
        stockPort.receiveReturn("CUSTOMER1","AS1","SALE","FUL",List.of(new StockPort.ReturnLine("SKU",1,StockPort.ReturnQuality.SALEABLE)));
        stockPort.receiveReturn("CUSTOMER1","AS1","SALE","FUL",List.of(new StockPort.ReturnLine("SKU",1,StockPort.ReturnQuality.SALEABLE)));
        stockPort.receiveReturn("CUSTOMER2","AS2","SALE","FUL",List.of(new StockPort.ReturnLine("SKU",1,StockPort.ReturnQuality.UNSALEABLE)));
        assertThat(stock()).isEqualTo(20);
        assertThat(effects.get(S,no).returnedQuantity()).isEqualByComparingTo("0");
    }

    @Test void legacyAbsoluteSettersAreBlockedBeforeAnyCatalogOrNewStoreRow() {
        register();var service=mock(MerchantGoodsServiceImpl.class,CALLS_REAL_METHODS);
        service.setOwnershipWrites(mock(StoreOwnershipWritePort.class));service.setStockWriters(guard);
        BizContext.set(new BizContext("E",Set.of(),Set.of(),Set.of("S"),"S",true));
        try {
            var tx=new TransactionTemplate(manager);
            assertThatThrownBy(()->tx.execute(status->{service.saveStock("E","G","SKU",100);return null;})).isInstanceOf(BizException.class);
            assertThatThrownBy(()->tx.execute(status->{service.saveStoreStock("E","S","G","SKU",100);return null;})).isInstanceOf(BizException.class);
            assertThat(stock()).isEqualTo(17);assertThat(count("prd_store_stock")).isZero();
        } finally {BizContext.clear();}
    }

    @Test void priceEditPreservesActualStockAndMappedUnitCannotChange() {
        register();var service=mock(MerchantGoodsServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"skuMapper",sessions.getMapper(SkuMapper.class));
        ReflectionTestUtils.setField(service,"storeStockMapper",sessions.getMapper(StoreStockMapper.class));
        ReflectionTestUtils.setField(service,"json",JsonMapper.builder().build());
        var tx=new TransactionTemplate(manager);
        tx.execute(status->{guard.lock("E");ReflectionTestUtils.invokeMethod(service,"saveSkus","E","G",
                List.of(new MerchantGoodsService.Sku("SKU",List.of(),200,null,999,null,null,null,null,null,"EA")),List.of(),true);return null;});
        assertThat(stock()).isEqualTo(17);assertThat(db.queryForObject("SELECT price FROM prd_sku",Long.class)).isEqualTo(200);
        assertThatThrownBy(()->tx.execute(status->{guard.lock("E");ReflectionTestUtils.invokeMethod(service,"saveSkus","E","G",
                List.of(new MerchantGoodsService.Sku("SKU",List.of(),300,null,999,null,null,null,null,null,"KG")),List.of(),true);return null;})).isInstanceOf(BizException.class);
        assertThat(db.queryForObject("SELECT sale_unit FROM prd_sku",String.class)).isEqualTo("EA");
    }

    @Test void reconciliationReportsCorruptionWithoutRepairingHeadOrCounter() {
        String no=register();effects.receive(S,"A","receipt",no,5);
        db.update("UPDATE prd_stock_replenishment SET received_quantity=6");
        var r=effects.reconcile(S,no,source("OUT",11));
        assertThat(r.issues()).contains("RECEIPT_LEDGER_MISMATCH");assertThat(stock()).isEqualTo(22);
        assertThat(effects.get(S,no).original().receivedQuantity()).isEqualByComparingTo("6");
    }
}
