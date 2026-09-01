package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.pay.service.SettleInvoiceService;
import ai.neargo.shop.pay.service.FeeRuleService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付域独立形态对外的<b>唯一入口</b>（除通道回调外）。
 *
 * <h2>四条硬要求，与 {@code JobHandlerEndpoint} 逐条一致</h2>
 * <ol>
 *   <li><b>绑内网</b>：nginx 不反代 {@code /internal/**}，调用方走
 *       {@code pay.svc.internal:8083}；</li>
 *   <li><b>共享密钥，不是用户令牌</b>：这个口<b>不认任何用户身份</b> ——
 *       支付域没有 controller、不读会话、不判权，那是主应用做完再把
 *       <b>收窄后的条件</b>传进来（见 PayHasNoControllerTest 的类注释）；</li>
 *   <li><b>不记 body</b>：调用链路的日志不该成为一个额外的数据出口；</li>
 *   <li><b>密钥没配就一律 401</b>，而不是「没配就不校验」。
 *       端点的装配也<b>不跟着密钥走</b> —— 跟着密钥的话，漏配时端点整个消失、
 *       调用方拿 404，症状指向「这个功能没实现」，离真因隔了两层。</li>
 * </ol>
 *
 * <h2>为什么在 pay-svc 而不是 pay-domain</h2>
 * 这些端点<b>只在独立形态下存在</b>：内嵌形态里主应用直接调 service，
 * 多一层 HTTP 没有任何意义。把它放进 pay-domain 的话，
 * 内嵌形态也会暴露出来 —— 那是一条没人用、也没人测的对外表面。
 *
 * <h2>今天只有费率，这是有意的</h2>
 * 切换按「反向依赖数」从小到大来：费率是 {@code FeeRuleServiceImpl}，
 * 它<b>一个业务侧 Port 都不依赖</b>，切过去之后不需要任何回调。
 * 而 {@code SettleServiceImpl} 依赖 5 个、{@code PointsServiceImpl} 依赖 6 个，
 * 那些要等反向 Port 有远程实现之后。
 */
@RestController
public class InternalPayEndpoint {

    private final FeeRuleService feeRuleService;
    private final SettleInvoiceService invoiceService;
    private final String token;

    public InternalPayEndpoint(FeeRuleService feeRuleService,
                               SettleInvoiceService invoiceService,
                               @Value("${shop.services.internal-token:}") String token) {
        this.feeRuleService = feeRuleService;
        this.invoiceService = invoiceService;
        this.token = token;
    }

    /** 全部费率版本，含历史 */
    @GetMapping("/internal/pay/fee-rules")
    public ResponseEntity<List<FeeRuleVO>> rules(
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(feeRuleService.rules());
    }

    /**
     * 某时刻实际生效的四格费率。
     *
     * @param at 毫秒。<b>必填，不给默认值</b> —— 服务端补 {@code now} 的话，
     *           调用方与被调方各自取一次「现在」，两个时刻之间跨过一次费率生效，
     *           算出来的就是两套数。时刻由调用方决定，这条链路上只有一个 now。
     */
    @GetMapping("/internal/pay/fee-rules/effective")
    public ResponseEntity<Map<String, Integer>> effective(
            @RequestParam long at,
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(feeRuleService.effectiveRates(at));
    }

    // ──────────────────────────────────────────── 商家结算发票（P-12.2.4）

    /** 开票申请列表 */
    @GetMapping("/internal/pay/settle-invoices")
    public ResponseEntity<PageData<SettleInvoiceVO>> invoices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(invoiceService.list(status, keyword, page, size));
    }

    /**
     * 开票。<b>写操作，而且切得动</b> —— 与费率的 addRule 不同。
     *
     * <p>它有状态机保护：只能从 {@code PENDING} 开票，重复调第二次是 {@code CONFLICT}
     * （那是有意的，源码注释写着「重复开票就是重复虚开，不做幂等早退，
     * 要让点第二次的人看见『已处理』」）。
     *
     * <p>所以远程化的风险不是「数据错」而是「状态不明」：
     * 超时后运营不知道成没成，他手动点第二次会看到 CONFLICT，
     * 再去列表里一看就知道已经开了。<b>前提是调用链上没有自动重试</b> ——
     * {@code InternalClient} 刻意不做重试，就是为了这类操作。
     *
     * <p>对比 {@code addRule}：那是「插新行」，重试会多出一版费率，
     * 而两版都在历史里、事后分不清哪次是重试。所以那个至今没切。
     */
    @PostMapping("/internal/pay/settle-invoices/{invoiceNo}/issue")
    public ResponseEntity<SettleInvoiceVO> issue(
            @PathVariable String invoiceNo,
            @RequestBody IssueReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(
                invoiceService.issue(invoiceNo, req.serialNo(), req.operatorNo()));
    }

    /** 驳回。同样靠状态机防重复 */
    @PostMapping("/internal/pay/settle-invoices/{invoiceNo}/reject")
    public ResponseEntity<SettleInvoiceVO> reject(
            @PathVariable String invoiceNo,
            @RequestBody RejectReq req,
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(
                invoiceService.reject(invoiceNo, req.reason(), req.operatorNo()));
    }

    /**
     * @param operatorNo 操作人由<b>主应用</b>解析后传进来 ——
     *                   支付域不认用户身份，这条链路上没有会话
     */
    public record IssueReq(String serialNo, String operatorNo) {
    }

    public record RejectReq(String reason, String operatorNo) {
    }

    /** 密钥没配时**一律拒绝** —— 「没配就不校验」等于这个口对任何人开放，且没有症状 */
    private boolean authorized(String given) {
        return !token.isBlank() && token.equals(given);
    }
}
