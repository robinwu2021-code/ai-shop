package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 独立部署支付（{@code shop.pay.deployment=standalone}）下的「已付款但订单还是待支付」兜底。
 *
 * <p>独立形态里，顾客的钱进的是 near-pay：渠道回调落在 near-pay，本进程的支付账是空的，
 * 所以 {@link OrderPaidReconciler}（读本地支付账）在这里永远「没有差异」。这个任务反过来扫本库里
 * 最近的待支付订单，逐笔按「订单号 + 下单人」向 near-pay 取成功记录（{@link SettlePort#successPayment}
 * 在独立形态下就是这个远程查询），取到就 {@link OrderRepairPort#markPaid}（幂等，只把待支付推到已支付）。
 *
 * <p>付款页轮询（{@code /mp/order/{no}/pay-result}）已经会顺带确认一次；这里兜住顾客付完就关页面的情况。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class RemoteOrderPaidReconcileJob implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(RemoteOrderPaidReconcileJob.class);
    static final String NAME = "order-paid-remote-recon";

    @Value("${shop.pay.deployment:embedded}")
    private String deployment;
    @Value("${shop.job.order-paid-remote-recon.lookback-hours:24}")
    private int lookbackHours;
    @Value("${shop.job.order-paid-remote-recon.limit:200}")
    private int limit;

    private final TradeMappers.OrderMapper orders;
    private final SettlePort settle;
    private final OrderRepairPort orderRepair;

    public RemoteOrderPaidReconcileJob(TradeMappers.OrderMapper orders, SettlePort settle, OrderRepairPort orderRepair) {
        this.orders = orders;
        this.settle = settle;
        this.orderRepair = orderRepair;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Bean
    public JobDeclaration remoteOrderPaidReconDeclaration() {
        return new JobDeclaration(NAME, "near-pay 已收款但订单未转已支付",
                "独立部署支付时：扫最近的待支付订单，逐笔向 near-pay 取成功记录，取到就补一次 markPaid（幂等）。"
                        + "付款页轮询已会顺带确认，这里兜住付完就关页面的顾客",
                "shop-app", "0 */2 * * * *", true,
                110, 120, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        if (!"standalone".equals(deployment)) {
            return JobResult.ok("内嵌支付形态，不需要（由 order-paid-recon 读本地支付账兜底）");
        }
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(lookbackHours);
        var waiting = DataScopeContext.executeWithoutScope(() -> orders.selectList(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getStatus, OrdOrder.WAIT_PAY)
                .ge(OrdOrder::getCreatedAt, since)
                .orderByDesc(OrdOrder::getId)
                .last("LIMIT " + Math.max(1, limit))));
        int repaired = 0;
        int failed = 0;
        for (OrdOrder order : waiting) {
            try {
                SettlePort.PaymentReceipt receipt = settle.successPayment(order.getOrderNo());
                if (receipt == null || !"SUCCESS".equals(receipt.status())) continue;
                orderRepair.markPaid(order.getOrderNo(), receipt.payChannel(), receipt.tradeNo());
                repaired++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("[{}] {} 补单失败：{}", NAME, order.getOrderNo(), e.getMessage());
            }
        }
        String detail = "扫描待支付 %d 笔（回看 %d 小时），补成已支付 %d 笔，失败 %d 笔"
                .formatted(waiting.size(), lookbackHours, repaired, failed);
        return failed > 0 ? JobResult.failed(detail, "补不动的要人工看：多半是订单已取消而钱已收到") : JobResult.ok(detail);
    }
}
