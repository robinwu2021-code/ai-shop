package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.dto.FinanceVOs.RefundSplitBackVO;
import java.util.List;

/**
 * 平台端 · 退款前的分账回退。
 *
 * <p><b>先回退分账，再退款</b>（ADR-002，顺序不可交换）：回退失败即中止且不退款 ——
 * 钱还在商家账户上就退给买家，平台要垫付这笔差额。
 */
public interface OpsRefundSplitBackAppService {

    /** 待回退队列 */
    List<RefundSplitBackVO> pending();

    /** 执行回退并退款。留痕标为重要 —— 这是一笔真的往外走的钱 */
    RefundSplitBackVO execute(String afterSaleNo);
}
