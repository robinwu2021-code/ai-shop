package ai.neargo.shop.pay.terminal.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.terminal.TerminalMappers.PayTerminalBatchMapper;
import ai.neargo.shop.pay.terminal.entity.PayTerminalBatch;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 终端日结（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code TerminalBatchService}）：终端上报当日笔数与金额，服务端按
 * 「本终端建的、当日付成功的单」汇总比对 → {@code MATCHED} / {@code MISMATCHED}。同一终端同一营业日重报覆盖上一次。
 *
 * <p>营业日按 UTC 日切（沙箱）；按门店市场时区切与差异进对账（{@code TerminalBatchAxis}）随 M7 / 复核 E 批。
 */
@Service
public class TerminalBatchService {

    private static final Set<String> PAID = Set.of("SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED");

    private final PayTerminalBatchMapper batches;
    private final PayOrderMapper orders;

    public TerminalBatchService(PayTerminalBatchMapper batches, PayOrderMapper orders) {
        this.batches = batches;
        this.orders = orders;
    }

    public record BatchReport(LocalDate bizDate, String currency, int count, long amountMinor, int voidCount, long voidMinor) {
    }

    public PayTerminalBatch report(PayCaller caller, BatchReport r) {
        if (caller.type() != CallerType.TERMINAL || r.bizDate() == null || r.currency() == null || r.count() < 0
                || r.amountMinor() < 0 || caller.operatorNo() == null || caller.operatorNo().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PayOrder> paid = DataScopeContext.executeWithoutScope(() -> orders.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getSourceType, CallerType.TERMINAL.name()).eq(PayOrder::getSourceId, caller.callerId())
                .eq(PayOrder::getCurrency, r.currency()).in(PayOrder::getStatus, PAID)
                .ge(PayOrder::getSucceededAt, r.bizDate().atStartOfDay())
                .lt(PayOrder::getSucceededAt, r.bizDate().plusDays(1).atStartOfDay())));
        int serverCount = paid.size();
        long serverMinor = paid.stream().mapToLong(o -> o.getPaidMinor() == null ? 0 : o.getPaidMinor()).sum();
        PayTerminalBatch row = find(caller.callerId(), r.bizDate());
        boolean fresh = row == null;
        if (fresh) {
            row = new PayTerminalBatch();
            row.setPayTerminalId(caller.callerId());
            row.setBizDate(r.bizDate());
        }
        row.setCurrency(r.currency());
        row.setReportedCount(r.count());
        row.setReportedMinor(r.amountMinor());
        row.setReportedVoidCount(r.voidCount());
        row.setReportedVoidMinor(r.voidMinor());
        row.setServerCount(serverCount);
        row.setServerMinor(serverMinor);
        row.setStatus(serverCount == r.count() && serverMinor == r.amountMinor() ? "MATCHED" : "MISMATCHED");
        row.setOperatorNo(caller.operatorNo());
        if (fresh) {
            batches.insert(row);
        } else {
            batches.updateById(row);
        }
        return row;
    }

    public PayTerminalBatch get(PayCaller caller, LocalDate bizDate) {
        PayTerminalBatch row = find(caller.callerId(), bizDate);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private PayTerminalBatch find(Long terminalId, LocalDate bizDate) {
        return batches.selectOne(Wrappers.<PayTerminalBatch>lambdaQuery().eq(PayTerminalBatch::getPayTerminalId, terminalId)
                .eq(PayTerminalBatch::getBizDate, bizDate));
    }
}
