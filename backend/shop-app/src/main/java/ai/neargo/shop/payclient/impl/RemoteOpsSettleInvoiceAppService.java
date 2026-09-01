package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.payclient.OpsSettleInvoiceAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.svc.InternalClient;
import ai.neargo.shop.svc.ServiceName;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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

    private static final int TIMEOUT_SEC = 5;
    private static final String BASE = "/internal/pay/settle-invoices";

    private final InternalClient client;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public RemoteOpsSettleInvoiceAppService(InternalClient client, AuditLogPort auditLogPort,
                                            ObjectMapper json) {
        this.client = client;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @Override
    public PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size) {
        StringBuilder q = new StringBuilder(BASE + "?page=" + page + "&size=" + size);
        if (status != null && !status.isBlank()) {
            q.append("&status=").append(enc(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            q.append("&keyword=").append(enc(keyword));
        }
        return json.readValue(call(client.get(ServiceName.PAY, q.toString(), TIMEOUT_SEC)),
                new TypeReference<PageData<SettleInvoiceVO>>() { });
    }

    @Override
    public SettleInvoiceVO issue(String invoiceNo, String serialNo) {
        String operator = SecurityUtils.currentUserNo();
        String body = json.writeValueAsString(new IssueReq(serialNo, operator));
        SettleInvoiceVO vo = json.readValue(
                call(client.post(ServiceName.PAY, BASE + "/" + invoiceNo + "/issue", body, TIMEOUT_SEC)),
                SettleInvoiceVO.class);
        // 先远程成功、再留痕 —— 反过来的话失败也会留下一条「已开票」，而审计记录必须是真的
        auditLogPort.record("SETTLE_INVOICE_ISSUE", invoiceNo, "流水号 " + vo.serialNo(), true);
        return vo;
    }

    @Override
    public SettleInvoiceVO reject(String invoiceNo, String reason) {
        String operator = SecurityUtils.currentUserNo();
        String body = json.writeValueAsString(new RejectReq(reason, operator));
        SettleInvoiceVO vo = json.readValue(
                call(client.post(ServiceName.PAY, BASE + "/" + invoiceNo + "/reject", body, TIMEOUT_SEC)),
                SettleInvoiceVO.class);
        auditLogPort.record("SETTLE_INVOICE_REJECT", invoiceNo, reason);
        return vo;
    }

    private String call(InternalClient.Result r) {
        if (r.ok()) {
            return r.body();
        }
        /*
         * 远程返回的业务错误（409 已处理、400 缺流水号）要**原样透出**，
         * 不能一律变成「系统开小差」—— 那三条校验（重复开票、没有流水号、
         * 超出已结算金额）每一条都是运营需要看见的原因。
         */
        if (r.outcome() == InternalClient.Outcome.REMOTE_ERROR) {
            log.warn("[pay-remote] 发票操作被支付域拒绝 status={} body={}", r.statusCode(),
                    r.statusCode());
            throw BizException.of(r.statusCode() == 409 ? ErrorCode.CONFLICT : ErrorCode.BAD_REQUEST);
        }
        log.error("[pay-remote] 发票操作失败 outcome={} msg={}", r.outcome(), r.message());
        throw BizException.of(ErrorCode.INTERNAL_ERROR);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record IssueReq(String serialNo, String operatorNo) { }

    private record RejectReq(String reason, String operatorNo) { }
}
