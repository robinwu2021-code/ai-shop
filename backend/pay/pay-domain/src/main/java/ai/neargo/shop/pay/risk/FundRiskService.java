package ai.neargo.shop.pay.risk;

import ai.neargo.shop.pay.entity.StlSettleBatch;

/**
 * 资金风控：判一批钱现在放出去有没有<b>已知的理由</b>不该放。
 *
 * <p>取向（[ADR-022]）：<b>宁可漏放一笔进入追偿流程，不可无依据地冻住一笔正常货款</b>。
 * 漏放有三层兜底（保证金 → 欠款抵扣 → 停放款），误伤一层都没有，
 * 而且被误伤的商家不会等我们排查完。
 *
 * <p><b>默认跑影子模式</b>（{@code shop.risk.fund.shadow} 默认 true）：
 * 只记录会拦谁、不真拦。不先跑影子就定阈值，等于拿真实商家的货款做实验。
 */
public interface FundRiskService {

    /**
     * 判一批，并记影子日志。
     *
     * @return 影子模式下<b>永远返回 PASS</b>（真实判定记在日志里）；
     *         真拦模式下返回实际判定
     */
    FundRisk.Verdict decide(StlSettleBatch batch);
}
