package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundResourceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Cash success remains committed while destination stock and coupon authorities recover independently. */
@Service
public class RefundResourceServiceImpl implements RefundResourceService {
    private final RefundExecutionStore executions;
    private final StockPort stock;
    private final StoreOrderMapper children;
    private final ObjectProvider<CouponPort> coupons;
    private final ObjectProvider<PlatformSwitchPort> switches;
    private final ObjectMapper json;
    private final String authority;
    private final TransactionTemplate independent;

    public RefundResourceServiceImpl(RefundExecutionStore executions, StockPort stock, StoreOrderMapper children,
            ObjectProvider<CouponPort> coupons, ObjectProvider<PlatformSwitchPort> switches, ObjectMapper json,
            @Value("${shop.inventory.stock-authority:PLATFORM}") String authority,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.executions = executions;
        this.stock = stock;
        this.children = children;
        this.coupons = coupons;
        this.switches = switches;
        this.json = json;
        this.authority = authority;
        this.independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void recover(String no) {
        try {
            Boolean restored = independent.execute(tx -> {
                OrdRefundExecution current = executions.read(no);
                if (current == null || !OrdRefundExecution.SUCCEEDED.equals(current.getStatus())
                        || OrdRefundExecution.DONE.equals(current.getResourceStatus())) {
                    return null;
                }
                return restore(current);
            });
            if (restored != null) {
                executions.resources(no, restored ? OrdRefundExecution.DONE : OrdRefundExecution.PENDING,
                        restored ? null : "STOCK_RESTORE_PENDING");
            }
        } catch (RuntimeException failure) {
            executions.resources(no, OrdRefundExecution.PENDING,
                    failure instanceof IllegalStateException ? failure.getMessage() : "RESOURCE_RECOVERY_FAILED");
        }
    }

    private boolean restore(OrdRefundExecution row) {
        boolean restored = true;
        if (Boolean.TRUE.equals(row.getRestoreStock())) {
            if (!Objects.equals(row.getStockAuthority(), authority)) {
                throw new IllegalStateException("STOCK_SOURCE_AUTHORITY_MISMATCH");
            }
            List<StockPort.SkuQty> items = json.readValue(row.getStockNeedsJson(), new TypeReference<>() { });
            OrdSubOrder child = DataScopeContext.executeWithoutScope(() -> children.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getSubOrderNo, row.getSubOrderNo()).last("LIMIT 1")));
            if (child != null && Integer.valueOf(2).equals(child.getInventoryLifecycleVersion())
                    && OrdSubOrder.WAIT_FULFILL.equals(child.getStatus())) {
                stock.releaseAllocated(child.getSubOrderNo(), child.getOrderNo());
                restored = true;
            } else {
                restored = stock.restoreOriginal(row.getExecutionNo(), row.getOrderNo(), items)
                        == StockPort.RestoreResult.COMPLETED;
            }
        }
        List<OrdSubOrder> siblings = DataScopeContext.executeWithoutScope(() -> children.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo, row.getOrderNo())));
        if (!siblings.isEmpty() && siblings.stream().allMatch(s -> OrdSubOrder.REFUNDED.equals(s.getStatus())
                || OrdSubOrder.CANCELLED.equals(s.getStatus()))) {
            CouponPort coupon = coupons.getIfAvailable();
            PlatformSwitchPort flags = switches.getIfAvailable();
            if (coupon != null && (flags == null || flags.bool("refund.return-coupon", true))) {
                coupon.release(row.getOrderNo());
            }
        }
        return restored;
    }
}
