package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayInternalApi.IssueReq;
import ai.neargo.shop.pay.client.PayInternalApi.RejectReq;
import ai.neargo.shop.payclient.OpsSettleInvoiceAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 商家结算发票的<b>远程</b>实现。切换的第二刀，与费率同形状。
 *
 * <h2>留痕留在主应用侧，不跟着调用过去</h2>
 * {@code auditLogPort.record(...)} 在这里做，理由与内嵌形态一样：
 * <b>「谁操作的」是主应用才知道的事</b> —— 支付域不认用户身份。
 * 把留痕挪到支付域的话，它得先知道操作人是谁，
 * 而那正是这套拆分刻意不给它的东西。
 *
 * <p>顺序也照旧：<b>先调远程，成功了再留痕</b>。反过来的话，
 * 远程失败仍然留下一条「已开票」的审计记录 —— 而审计记录的全部价值
 * 就是它必须是真的。
 *
 * <h2>写操作切得动，因为它有状态机保护</h2>
 * {@code issue} / {@code reject} 只能从 {@code PENDING} 出发，
 * 重复调第二次是 {@code CONFLICT}。所以远程化的风险不是「数据错」
 * 而是「状态不明」：超时后运营不知道成没成，他点第二次会看到「已处理」，
 * 再看列表就清楚了。
 *
 * <p><b>前提是这条链上没有自动重试</b>。{@code InternalClient} 刻意不做重试 ——
 * 自动重试会把「状态不明」变成「运营完全不知道发生过什么」。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOpsSettleInvoiceAppService implements OpsSettleInvoiceAppService {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpsSettleInvoiceAppService.class);

    private final PayInternalApi pay;
    private final AuditLogPort auditLogPort;

    public RemoteOpsSettleInvoiceAppService(PayInternalApi pay, AuditLogPort auditLogPort) {
        this.pay = pay;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size) {
        // 空串与 null 同义：不带这个参数，服务端按「不筛」处理（与迁移前一致）
        return call(() -> pay.settleInvoices(blankToNull(status), blankToNull(keyword), page, size));
    }

    @Override
    public SettleInvoiceVO issue(String invoiceNo, String serialNo) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = call(() -> pay.issue(invoiceNo, new IssueReq(serialNo, operator)));
        // 先远程成功、再留痕 —— 反过来的话失败也会留下一条「已开票」，而审计记录必须是真的
        auditLogPort.record("SETTLE_INVOICE_ISSUE", invoiceNo, "流水号 " + vo.serialNo(), true);
        return vo;
    }

    @Override
    public SettleInvoiceVO reject(String invoiceNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        SettleInvoiceVO vo = call(() -> pay.reject(invoiceNo, new RejectReq(reason, operator)));
        auditLogPort.record("SETTLE_INVOICE_REJECT", invoiceNo, reason);
        return vo;
    }

    private <R> R call(Supplier<R> invocation) {
        try {
            return ServiceCalls.call(ServiceName.PAY, invocation);
        } catch (ServiceCallException e) {
            /*
             * 远程返回的业务错误（409 已处理、400 缺流水号）要**原样透出**，
             * 不能一律变成「系统开小差」—— 那三条校验（重复开票、没有流水号、
             * 超出已结算金额）每一条都是运营需要看见的原因。
             */
            if (e.outcome() == CallOutcome.REMOTE_ERROR && e.statusCode() > 0) {
                log.warn("[pay-remote] 发票操作被支付域拒绝 status={}", e.statusCode());
                throw BizException.of(e.statusCode() == 409 ? ErrorCode.CONFLICT : ErrorCode.BAD_REQUEST);
            }
            log.error("[pay-remote] 发票操作失败 outcome={} msg={}", e.outcome(), e.getMessage());
            throw BizException.of(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
