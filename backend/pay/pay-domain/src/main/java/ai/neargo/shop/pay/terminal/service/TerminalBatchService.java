package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.terminal.entity.PayTerminalBatch;
import java.time.LocalDate;

/**
 * 终端日结（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code TerminalBatchService}）：终端上报当日笔数与金额，服务端按
 * 「本终端建的、当日付成功的单」汇总比对 → {@code MATCHED} / {@code MISMATCHED}。同一终端同一营业日重报覆盖上一次。
 *
 * <p>营业日按 UTC 日切（沙箱）；按门店市场时区切与差异进对账（{@code TerminalBatchAxis}）随 M7 / 复核 E 批。
 */
public interface TerminalBatchService {

    record BatchReport(LocalDate bizDate, String currency, int count, long amountMinor, int voidCount, long voidMinor) {
    }

    PayTerminalBatch report(PayCaller caller, BatchReport r);

    PayTerminalBatch get(PayCaller caller, LocalDate bizDate);
}
