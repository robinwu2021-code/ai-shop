package ai.neargo.shop.pay.client;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * shop-app 调支付域独立进程（{@code shop.pay.deployment=standalone}）的全部入口。
 *
 * <p>放在 pay-domain 而不是 shop-app：服务端（pay-svc）与客户端（shop-app）都看得见它，
 * 两边的请求体 {@link IssueReq} / {@link RejectReq} 也就只有这一份 —— 此前是服务端一份 public record、
 * 客户端一份 private record，字段名靠人对齐。
 *
 * <h2>不要给写操作加重试</h2>
 * {@code issue} / {@code reject} 只能从 PENDING 出发，重复执行第二次会失败或产生重复记录；
 * 在有幂等键之前，超时之后「成没成」只能由人去查。{@code @Retryable} 只允许加在 GET 方法上。
 */
public interface PayInternalApi {

    /** 全部费率版本，含历史 */
    @GetExchange(PayInternalPaths.FEE_RULES)
    List<FeeRuleVO> feeRules();

    /** 某一时刻生效的费率：{@code 经营模式|流量来源 → 费率（基点）} */
    @GetExchange(PayInternalPaths.FEE_RULES_EFFECTIVE)
    Map<String, Integer> effectiveRates(@RequestParam("at") long at);

    /** status / keyword 为 {@code null} 时不带这个参数（服务端按「不筛」处理） */
    @GetExchange(PayInternalPaths.SETTLE_INVOICES)
    PageData<SettleInvoiceVO> settleInvoices(@RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "keyword", required = false) String keyword,
                                             @RequestParam("page") long page,
                                             @RequestParam("size") long size);

    @PostExchange(PayInternalPaths.SETTLE_INVOICE_ISSUE)
    SettleInvoiceVO issue(@PathVariable("invoiceNo") String invoiceNo, @RequestBody IssueReq req);

    @PostExchange(PayInternalPaths.SETTLE_INVOICE_REJECT)
    SettleInvoiceVO reject(@PathVariable("invoiceNo") String invoiceNo, @RequestBody RejectReq req);

    record IssueReq(String serialNo, String operatorNo) {
    }

    record RejectReq(String reason, String operatorNo) {
    }
}
