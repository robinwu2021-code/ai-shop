package ai.neargo.shop.settle;

import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;

import java.util.List;

/** 结算与分账（[API 清单 §3.10 / §4.12]）。 */
public interface SettleService {

    /** 支付成功后按子单生成结算单。**幂等**：事件重投不会重复生成。 */
    int generateForOrder(String orderNo);

    /** 执行分账。**幂等**：重复执行不会重复打款。 */
    void executeSplit(String settleNo);

    /**
     * 分账回退（售后退款前必须先做）。
     *
     * @return false 表示回退失败、已转人工。**调用方必须据此停止退款** ——
     *         钱已经分给商家了还退给用户，平台就要垫付这笔差额
     */
    boolean reverseSplit(String subOrderNo);

    /** 原路退款。返回退款单号 */
    String refund(String subOrderNo, long amountMinor, String reason);

    /**
     * @param storeNos 门店作用域。<b>空集合不等于不过滤</b> —— 与订单侧同一个越权陷阱；
     *                 单店主体一律不收窄（存量流水没有 store_no，一筛就全没了）
     */
    List<SettleBillVO> merchantBills(String merchantNo, java.util.Collection<String> storeNos);

    SettleBillVO merchantBill(String merchantNo, String settleNo);

    RateCardVO rateCard();

    /** 分账日志条数（按动作）。测试与运营排查用。 */
    long splitLogCount(String settleNo, String action);
}
