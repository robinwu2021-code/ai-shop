package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.dto.PurchaseInvoiceVO;
import ai.neargo.shop.pay.dto.RateCardVO;
import ai.neargo.shop.pay.dto.SettleBillVO;
import ai.neargo.shop.pay.dto.StatementVO;
import java.util.List;
import java.util.Map;

/**
 * B 端结算的 app service —— <b>门店数据域的唯一落点</b>。
 *
 * <h2>为什么把它从 controller 里搬出来</h2>
 * 搬之前，「默认当前门店、{@code allStores=true} 才看全部」这段收窄
 * 在 {@code BizSettleController} 里<b>逐字出现了两次</b>（/bills 与 /income）。
 * 那段代码自己的注释写着「两处不同的话，总览的数和明细列表加起来就对不上，
 * 而那种错查起来比两处都错更费劲」—— 它说得对，而防住它的办法
 * 不是把这句话写在注释里，是让这段代码只存在一份。
 *
 * <p>更要紧的是收窄漏掉时的症状：<b>商家看到别家店的钱，页面照常渲染，没有任何报错。</b>
 * 越权在这里不是异常路径，是「多返回了几行」。所以它需要一个能被断言的落点，
 * 而 controller 里散布的三行拼装断言不了。
 *
 * <h2>它同时是形态切换的接缝</h2>
 * 支付域独立部署那天，换的是这一层注入的东西
 * （{@code LocalPayAdapter} → {@code RemotePayClient}），
 * controller 一行都不用改。controller 直接注入 {@code SettleService} 的话，
 * 那 12 个 controller 就是 12 个要改的地方。
 */
public interface BizSettleAppService {

    /**
     * 结算流水。
     *
     * @param allStores {@code true} = 看授权范围内全部门店；否则只看当前门店。
     *                  <b>「全部」对老板和店员不是一回事</b> —— 老板的全部是主体名下所有店，
     *                  店员的只是他被授权的那几家，由 {@code BizContext} 决定
     */
    List<SettleBillVO> bills(Boolean allStores);

    /**
     * 收入总览：按状态汇总的四个数。
     *
     * <p><b>门店收窄与 {@link #bills} 走同一个方法</b> —— 这是它们搬到这一层的理由。
     */
    SettleService.IncomeSummaryVO income(Boolean allStores);

    /** 我的账期批次：这一批什么时候放、卡在哪。商家问客服最多的就是这个。 */
    List<SettleBatchService.BatchVO> batches();

    /** 单笔结算明细。按主体收窄，不按门店 —— 已经拿到单号就说明是从自己的列表点进来的 */
    SettleBillVO bill(String settleNo);

    /** 费率说明。与商家无关，是平台的一条设置 */
    RateCardVO rateCard();

    /** 平台开票信息，供应商照着它开票 */
    Map<String, String> invoiceTitle();

    /** 提交进项票。一张票覆盖该周期全部已对账待开票的单 */
    PurchaseInvoiceVO submitInvoice(SettleService.SubmitInvoiceCommand command);

    /** 我提交过的票，含驳回原因 */
    List<PurchaseInvoiceVO> myInvoices();

    /** 对账单。**这是凭证不是报表** —— 小微没有发票、没有对公流水 */
    StatementVO statement(String period);

    /**
     * 我能提多少 + 我的提现记录。
     *
     * <p>两个数一起给：只给「可提余额」的话，商家看不到上一笔在审的，
     * 会以为钱少了一截；只给记录的话他得自己算还能提多少。
     */
    WithdrawPageVO myWithdraws();

    /** 申请提现。金额单位为分 */
    ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO applyWithdraw(long amountMinor);

    /**
     * @param withdrawableMinor 现在能提多少（已到账结算款 − 在途提现）
     * @param minAmountMinor    单笔下限。**端上要用它禁用按钮**，
     *                          不然商家点了才知道太少
     * @param records           历史申请，倒序
     */
    record WithdrawPageVO(long withdrawableMinor, long minAmountMinor,
                          List<ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO> records) {
    }
}
