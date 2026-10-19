package ai.neargo.shop.pay.settle;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 向分账通道发一条指令并留痕（从 {@code SettleServiceImpl#callProvider} 抽出，金额由调用方给）。
 *
 * <p><b>幂等在这一层</b>：{@code requestNo} 已有成功流水就不再发（{@code uk_request_no} 兜底），
 * 不依赖服务商的幂等实现。<b>失败也落流水</b>：「发过但失败」与「压根没发」是两个排查方向。
 */
@Component
public class SplitInstructionSender {

    private final SplitLogMapper splitLogMapper;
    private final SplitGateway gateway;
    private final MerchantQueryPort merchantQueryPort;

    public SplitInstructionSender(SplitLogMapper splitLogMapper, SplitGateway gateway,
                                  MerchantQueryPort merchantQueryPort) {
        this.splitLogMapper = splitLogMapper;
        this.gateway = gateway;
        this.merchantQueryPort = merchantQueryPort;
    }

    public boolean send(String action, StlBill bill, String requestNo, long amountMinor) {
        if (alreadySucceeded(requestNo)) {
            return true;
        }
        String payTo = payToOf(bill);
        SplitGateway.Result result = switch (action) {
            case StlSplitLog.REVERSE -> gateway.reverse(bill.getSubOrderNo(), payTo, amountMinor, requestNo);
            case StlSplitLog.SUBSIDY -> gateway.subsidy(bill.getSubOrderNo(), payTo, amountMinor, requestNo);
            case StlSplitLog.SUBSIDY_RETURN ->
                    gateway.subsidyReturn(bill.getSubOrderNo(), payTo, amountMinor, requestNo);
            default -> gateway.split(bill.getSubOrderNo(), payTo, amountMinor, requestNo);
        };
        log(action, bill, requestNo, amountMinor, result);
        return result.success();
    }

    private boolean alreadySucceeded(String requestNo) {
        return DataScopeContext.executeWithoutScope(() ->
                splitLogMapper.selectCount(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getRequestNo, requestNo)
                        .eq(StlSplitLog::getResult, "SUCCESS"))) > 0;
    }

    /**
     * 收款号取<b>账单上的快照</b>，不是「这家店现在用哪个号」：商家改号之后，
     * 历史流水仍要打回当初收款的那个账户 —— 退款尤其，从新账户扣两个账户各错一笔且方向相反。
     * 缺少原收款快照时暂停执行，不能使用当前账户替代。
     */
    private String payToOf(StlBill bill) {
        String payTo = bill.getPayMerchantNo();
        if (payTo == null || payTo.isBlank()) {
            throw new IllegalStateException("SETTLEMENT_RECIPIENT_REQUIRED");
        }
        return payTo;
    }

    private void log(String action, StlBill bill, String requestNo, long amountMinor, SplitGateway.Result result) {
        StlSplitLog entry = new StlSplitLog();
        entry.setSettleNo(bill.getSettleNo());
        entry.setSubOrderNo(bill.getSubOrderNo());
        entry.setSplitAction(action);
        entry.setAmountMinor(amountMinor);
        // 失败的指令也要有唯一 requestNo，但不能挡住重试 —— 加时间戳后缀
        entry.setRequestNo(result.success() ? requestNo : requestNo + "-F" + System.currentTimeMillis());
        entry.setResult(result.success() ? "SUCCESS" : "FAILED");
        entry.setProviderNo(result.providerNo());
        entry.setMessage(result.message());
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> splitLogMapper.insert(entry));
    }
}
