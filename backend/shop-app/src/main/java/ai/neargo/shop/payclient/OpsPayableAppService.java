package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.dto.PurchaseInvoiceVO;
import ai.neargo.shop.pay.dto.SettleBillVO;
import java.util.List;
import java.util.Map;

/**
 * 平台端 · 自营应付账款的 app service —— <b>资金动作留痕的落点</b>。
 *
 * <h2>这一层收的是什么</h2>
 * 搬之前，六个写动作在 controller 里都是同一个三步：
 * 取操作人 → 调支付域 → {@code auditLogPort.record(...)}。
 * 留痕跟着 HTTP 层走，于是它的存在与否取决于<b>写 controller 的人记不记得</b>——
 * 而漏写不会报错、不影响返回值，界面上一切正常。
 *
 * <p>钱出账的登记漏了留痕，事后要回答「谁在什么时候登记了哪张凭证」时
 * 只剩下一行 {@code paid_at}。<b>那不是查得慢，是查不到。</b>
 *
 * <p>搬到这一层之后，「每个资金动作都要留痕」才有一个能被断言的位置：
 * 六个方法都在同一个类里，闸门扫得到；散在 controller 里时，
 * 它和「这个方法恰好不需要留痕」长得一模一样。
 *
 * <h2>系统只登记不划转</h2>
 * 真正的打款是财务在网银执行的。这里的每个动作都是<b>对事实的登记</b>，
 * 不是对钱的操作 —— 所以留痕的价值比动作本身还高：
 * 登记错了钱不会动，但账会错，而账错是要人去追的。
 */
public interface OpsPayableAppService {

    /**
     * @param status   为空给全部；{@code PENDING_RECON} 就是待对账队列
     * @param entityNo 为空给全部供应商
     */
    List<SettleBillVO> list(String status, String entityNo);

    /** 对账确认。确认的含义是「双方认这个数」，之后金额不该再变 */
    SettleBillVO confirm(String settleNo);

    /**
     * 登记已付。<b>票到付款</b> —— 进项票未核验通过的单会被拒。
     *
     * @param paymentRef 凭证号。没有它，事后对不上银行流水，也说不清是谁付的
     */
    SettleBillVO markPaid(String settleNo, String paymentRef);

    /**
     * 标记无票供应商，之后这张单可以直接付款。
     *
     * <p><b>标出而不是禁止</b>：现实中总会有例外，禁止会逼人绕过系统；
     * 标出则让每一笔例外都是被看见的。
     */
    SettleBillVO markNoInvoice(String settleNo, String reason);

    /** @param status 为空给全部；{@code SUBMITTED} 就是待核验队列 */
    List<PurchaseInvoiceVO> invoices(String status);

    /** 核验通过。会比对开票方名称与供应商主体名（三流一致里机器可判的那部分） */
    PurchaseInvoiceVO verifyInvoice(String invoiceNo);

    /** 驳回。原因必填 —— 供应商得知道是抬头错了、金额不符还是影像看不清 */
    PurchaseInvoiceVO rejectInvoice(String invoiceNo, String reason);

    /** 平台开票信息，供应商照着它开票 */
    Map<String, String> invoiceTitle();

    /** 保存平台开票信息。公司全称与税号必填 —— 缺了供应商开不出票 */
    Map<String, String> saveInvoiceTitle(Map<String, String> fields);
}
