package ai.neargo.shop.pay.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO;

/**
 * 提现审批与个税代扣规则（矩阵 P-12.2.1 / 12.2.2 / 12.2.3）。
 *
 * <p><b>本服务不打款。</b> 分账参数的书面口径还没拿到（待完成功能清单 §四 B7），
 * 而 B-12.5 定的是「一期只记账、线下结算」。这里做的是状态机 + 留痕。
 */
public interface WithdrawService {

    /**
     * 提现单列表。
     *
     * @param status  为空给全部
     * @param keyword 匹配提现单号 / 商家号 / 商家名
     */
    /**
     * <b>商家申请提现。</b>
     *
     * <h2>这个方法此前不存在</h2>
     * 提现单在生产代码里<b>从没被创建过</b> —— C 端与 B 端都没有申请入口，
     * 本接口也只有 {@code list} / {@code decide}。
     * 于是运营端的提现审批页<b>永远是空的</b>，{@code decide} 永远不作用于真实数据。
     *
     * <p>{@code StlWithdraw} 的类注释说「这张表不打款」，那是关于<b>打款</b>的
     * 设计决定（{@code APPROVED → PAID} 只能由渠道回执推进）；
     * 而这里缺的是<b>申请</b>。两件事不能混：不打款是刻意的，没有申请入口不是。
     *
     * <h2>三道校验，各拦一类事</h2>
     * <ol>
     *   <li><b>金额下限</b>：低于它渠道手续费比本金还贵；</li>
     *   <li><b>可提余额</b>：已到账的结算款减去在途的提现。
     *       不减在途的话，商家连点两次就能把同一笔钱申请两遍 ——
     *       而两张单各自看都是合规的；</li>
     *   <li><b>唯一在途单</b>：已有未终态的提现时不许再提。
     *       这一条与 ② 重复了一半，但它给出的错误信息完全不同 ——
     *       「你还有一笔在审核」比「余额不足」有用得多。</li>
     * </ol>
     *
     * @param amountMinor 申请金额（分）
     * @return 落库后的提现单
     */
    WithdrawVO apply(String entityNo, long amountMinor, String operatorNo);

    /**
     * 这家商家现在能提多少（分）。
     *
     * <p>= 已到账的结算款 − 在途/已完成的提现。
     * <b>端上要拿它算「还能提多少」</b>，只给一个总额的话商家得自己减。
     */
    long withdrawableMinor(String entityNo);

    PageData<WithdrawVO> list(String status, String keyword, long page, long size);

    /**
     * 审批一笔提现。<b>运营端唯一会把钱批出去的动作，校验最密</b>。
     *
     * <p>通过时六道校验，每一道都对应一种「批了也打不出去」或「不该打」：
     * 状态机 · 余额快照 · 收款账户已报备 · 未封禁 · 单笔下限 · 大额复核说明。
     * 驳回时只要求写原因 —— 它原样回商家 B 端。
     *
     * <p>⚠️ 通过后落 {@code APPROVED} 而不是 {@code PAID}：打款结果来自渠道回执，
     * 让人手动置为「已打款」就等于允许在钱没到账时把单子做平。
     *
     * @param remark 驳回原因 / 大额复核说明。<b>字段名不叫 reason</b> ——
     *               运营端契约发的是 {@code remark}，改名会让守卫红且真实调用发不出去
     */
    WithdrawVO decide(String withdrawNo, boolean pass, String remark, String operatorNo);

    /** 个税代扣规则。没配过时给缺省值 —— 参数表少一行不该让整个页面打不开。 */
    TaxRuleVO taxRule();

    /**
     * 保存个税代扣规则。
     *
     * @param rate 万分比。硬上限 {@code MAX_RATE_BP}（45%）——
     *             一个手滑多打的零会让每一笔提现都被扣光
     */
    TaxRuleVO saveTaxRule(long threshold, long rate, String operatorNo);
}
