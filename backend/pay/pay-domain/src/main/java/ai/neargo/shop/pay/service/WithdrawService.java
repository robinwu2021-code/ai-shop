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
