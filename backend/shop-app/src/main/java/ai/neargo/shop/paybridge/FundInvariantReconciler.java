package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.service.FundInvariantService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Cash-bill consistency only. Points recovery owns journal, holds and refund obligations. */
@Service
public class FundInvariantReconciler {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantReconciler.class);

    private final FundInvariantService payAccounts;
    private final SettleSourcePort tradeSource;
    private final SettlePort settlePort;

    public FundInvariantReconciler(FundInvariantService payAccounts, SettleSourcePort tradeSource,
                                   SettlePort settlePort) {
        this.payAccounts = payAccounts;
        this.tradeSource = tradeSource;
        this.settlePort = settlePort;
    }

    public Result scan(long since, int limit) {
        // ── I1：每个已支付子单必有结算单
        List<SettleSourcePort.PaidSubOrder> paid = tradeSource.paidSubOrdersSince(since, limit);
        Set<String> withBill = payAccounts.subOrdersWithBill(
                paid.stream().map(SettleSourcePort.PaidSubOrder::subOrderNo).toList());
        List<SettleSourcePort.PaidSubOrder> missing = paid.stream()
                .filter(p -> !withBill.contains(p.subOrderNo()))
                .toList();

        /*
         * **按主单去重再补。** generateForOrder 是按主单做的：
         * 一个主单缺三个子单的结算单时，调三次和调一次结果相同（它幂等），
         * 但日志里会显示「补了 3 张」而实际只补了一轮 —— 那个数会误导下一个人。
         */
        Set<String> orderNos = new LinkedHashSet<>(
                missing.stream().map(SettleSourcePort.PaidSubOrder::orderNo).toList());
        int repaired = 0;
        for (String orderNo : orderNos) {
            // 逐单独立 try：一个主单补不出来（数据本身有问题）不能把整轮带走
            try {
                repaired += settlePort.generateForOrder(orderNo);
            } catch (RuntimeException e) {
                log.warn("[fund-invariant] I1 补生成失败 orderNo={}：{}", orderNo, e.getMessage());
            }
        }
        long oldestMissingAt = missing.stream()
                .mapToLong(SettleSourcePort.PaidSubOrder::paidAt)
                .filter(x -> x > 0)
                .min().orElse(0L);

        // ── I2：每张结算单必有对应的已支付子单
        List<String> billSubOrders = payAccounts.billSubOrderNosSince(since, limit);
        List<String> orphan = billSubOrders.isEmpty() ? List.of()
                : tradeSource.notPaidAmong(billSubOrders);
        if (!orphan.isEmpty()) {
            log.error("[fund-invariant] **I2 违反 {} 条**：结算单对不上已支付子单，"
                            + "需人工判断（不自动处理）。样本：{}",
                    orphan.size(), orphan.subList(0, Math.min(5, orphan.size())));
        }

        return new Result(paid.size(), missing.size(), repaired,
                billSubOrders.size(), orphan.size(), oldestMissingAt);
    }

    public record Result(int scannedPaid, int missingBill, int repairedBill,
                         int scannedBills, int orphanBill, long oldestMissingAt) {

        public boolean scannedAnything() {
            return scannedPaid > 0 || scannedBills > 0;
        }
    }
}
