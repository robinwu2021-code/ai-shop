package ai.neargo.shop.pay.api.terminal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.terminal.entity.PayTerminalBatch;
import ai.neargo.shop.pay.terminal.service.TerminalBatchService;
import ai.neargo.shop.pay.terminal.service.TerminalBatchService.BatchReport;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POS 终端日结（pay-guard 开发计划 M6.4）：上报当日笔数与金额，服务端比对；收银员取自 {@code X-Operator} */
@RestController
@RequestMapping("/terminal/pay/v1/batches")
public class TerminalBatchController {

    private final TerminalBatchService batches;

    public TerminalBatchController(TerminalBatchService batches) {
        this.batches = batches;
    }

    record ReportReq(String bizDate, String currency, Integer count, Long amountMinor, Integer voidCount, Long voidMinor) {
    }

    record BatchVO(String bizDate, String currency, int reportedCount, long reportedMinor, Integer serverCount,
                   Long serverMinor, String status) {

        static BatchVO of(PayTerminalBatch b) {
            return new BatchVO(b.getBizDate().toString(), b.getCurrency(), b.getReportedCount(), b.getReportedMinor(),
                    b.getServerCount(), b.getServerMinor(), b.getStatus());
        }
    }

    @PostMapping
    public BatchVO report(PayCaller caller, @RequestBody ReportReq req) {
        return BatchVO.of(batches.report(caller, new BatchReport(date(req.bizDate()), req.currency(),
                req.count() == null ? -1 : req.count(), req.amountMinor() == null ? -1 : req.amountMinor(),
                req.voidCount() == null ? 0 : req.voidCount(), req.voidMinor() == null ? 0 : req.voidMinor())));
    }

    @GetMapping("/{bizDate}")
    public BatchVO get(PayCaller caller, @PathVariable String bizDate) {
        return BatchVO.of(batches.get(caller, date(bizDate)));
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
