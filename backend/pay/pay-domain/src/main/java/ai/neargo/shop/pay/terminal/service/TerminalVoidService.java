package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.mapper.terminal.TerminalMappers.PayTerminalMapper;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

/**
 * 终端撤销 / 退款（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code TerminalVoidService}）：先验店长授权码，再看单终端日退款上限，
 * 然后走与其它入口同一条退款单流程（可退余额、复核规则、执行器）—— 撤销在沙箱里按当日退款处理（刷卡渠道的撤销接口随 Q12 接入）。
 * 店长记在退款单的 {@code authorized_by}，收银员记 {@code requested_by}。
 */
@Service
public class TerminalVoidService {

    private final ManagerCodeService managerCodes;
    private final RefundService refunds;
    private final PayRefundMapper refundRows;
    private final PayTerminalMapper terminals;

    public TerminalVoidService(ManagerCodeService managerCodes, RefundService refunds, PayRefundMapper refundRows,
                               PayTerminalMapper terminals) {
        this.managerCodes = managerCodes;
        this.refunds = refunds;
        this.refundRows = refundRows;
        this.terminals = terminals;
    }

    /** @param kind {@code VOID}（当日撤销）/ {@code REFUND}（隔日退款） */
    public PayRefund refund(PayCaller caller, String kind, String payOrderNo, long amountMinor, String managerCode,
                            String reason, String outRefundNo) {
        if (caller.type() != CallerType.TERMINAL || caller.storeScope().size() != 1 || amountMinor <= 0
                || !("VOID".equals(kind) || "REFUND".equals(kind))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Long storeId = caller.storeScope().iterator().next();
        Long manager = managerCodes.verify(storeId, managerCode);
        PayTerminal t = terminals.selectById(caller.callerId());
        long limit = t == null || t.getDailyRefundLimitMinor() == null ? 0 : t.getDailyRefundLimitMinor();
        if (refundedToday(caller.callerId()) + amountMinor > limit) {
            throw BizException.of(ErrorCode.PAY_FORBIDDEN);
        }
        String out = outRefundNo == null || outRefundNo.isBlank() ? BizKey.next("TMR") : outRefundNo;
        PayRefund r = refunds.request(caller, new RefundRequestCmd(payOrderNo, out, out, amountMinor,
                (reason == null ? "" : reason + " ") + "[" + kind + "]"));
        refundRows.update(null, Wrappers.<PayRefund>lambdaUpdate().eq(PayRefund::getId, r.getId())
                .set(PayRefund::getAuthorizedBy, "MANAGER:" + manager));
        r.setAuthorizedBy("MANAGER:" + manager);
        return r;
    }

    /** 本终端今天（UTC 日）已发起的退款额；失败的不算 */
    private long refundedToday(Long terminalId) {
        return refundRows.selectList(Wrappers.<PayRefund>lambdaQuery().eq(PayRefund::getSourceType, CallerType.TERMINAL.name())
                        .eq(PayRefund::getSourceId, terminalId).ne(PayRefund::getStatus, "FAILED")
                        .ge(PayRefund::getCreatedAt, LocalDate.now(ZoneOffset.UTC).atStartOfDay()))
                .stream().mapToLong(x -> x.getAmountMinor() == null ? 0 : x.getAmountMinor()).sum();
    }
}
