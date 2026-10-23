package ai.neargo.shop.trade.order;

import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.impl.RefundResourceServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class RefundResourceFrozenAllocationTest {
    @Test @SuppressWarnings("unchecked") void fullFinalAndPartialUnshippedCasesReleaseFrozenOriginalTargetsEvenAfterChildRefunded() {
        for(String status:List.of("REFUNDED","WAIT_FULFILL")) {
            var store=mock(RefundExecutionStore.class); var stock=mock(StockPort.class); var children=mock(StoreOrderMapper.class);
            ObjectProvider<CouponPort> coupons=mock(ObjectProvider.class); ObjectProvider<PlatformSwitchPort> switches=mock(ObjectProvider.class);
            var manager=mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            var json=new ObjectMapper(); int quantity=status.equals("REFUNDED")?3:1;
            var target=new StockPort.SkuQty("SKU1",quantity,"ST1");
            var plan=new RefundPlan("S1",List.of(new RefundLine(1,quantity,quantity*10)),0,List.of(),List.of(target));
            var row=new OrdRefundExecution(); row.setExecutionNo("RF1"); row.setOrderNo("O1"); row.setSubOrderNo("S1");
            row.setStatus("SUCCEEDED"); row.setResourceStatus("PENDING"); row.setRestoreStock(true); row.setStockAuthority("INVENTORY");
            row.setPlanJson(json.writeValueAsString(plan)); row.setStockNeedsJson(json.writeValueAsString(List.of(target)));
            var child=new OrdSubOrder(); child.setOrderNo("O1"); child.setSubOrderNo("S1"); child.setStatus(status); child.setInventoryLifecycleVersion(2);
            when(store.read("RF1")).thenReturn(row); when(children.selectOne(any())).thenReturn(child);
            when(children.selectList(any())).thenReturn(List.of(child));
            var service=new RefundResourceServiceImpl(store,stock,children,coupons,switches,json,"INVENTORY",manager);
            service.recover("RF1");
            verify(stock).releaseAllocatedTo("S1","O1",List.of(target));
            verify(stock,never()).restoreOriginal(anyString(),anyString(),anyList());
            verify(store).resources("RF1","DONE",null);
        }
    }
}
