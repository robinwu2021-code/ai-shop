package ai.neargo.shop.payclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO;

/**
 * 平台端 · 提现审批与个税规则。
 *
 * <p>{@link #decide} 是<b>运营端唯一会把钱批出去的动作</b>，
 * {@link #saveTaxRule} 改的是<b>所有后续提现</b>被扣掉多少 ——
 * 两个都按重要留痕记，理由不同但结论一样：事后一定会有人来问。
 */
public interface OpsWithdrawAppService {

    TaxRuleVO taxRule();

    /**
     * 保存个税代扣规则。
     *
     * @param threshold 起征点（分），为空按 0
     * @param rate      税率（万分比），为空按 0
     */
    TaxRuleVO saveTaxRule(Long threshold, Long rate);
}
