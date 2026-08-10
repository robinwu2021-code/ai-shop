package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.AfterSaleVO;

import java.util.List;

/** 售后（[API 清单 §2.6 / §3.7]）。**子单粒度**：一次售后只针对一个商家。 */
public interface AfterSaleService {

    List<String> reasons();

    AfterSaleVO apply(String subOrderNo, ApplyCommand cmd);

    List<AfterSaleVO> myList();

    AfterSaleVO detail(String afterSaleNo);

    AfterSaleVO cancel(String afterSaleNo);

    AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo);

    AfterSaleVO escalate(String afterSaleNo, String appeal);

    List<AfterSaleVO> merchantList(String merchantNo, String status);

    /** 同意。**退货退款的「同意」不等于退钱** —— 要等收到货（见实现）。 */
    AfterSaleVO approve(String merchantNo, String afterSaleNo);

    /** 驳回。{@code remark} 必填 —— 用户要据此决定是否申诉。 */
    AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark);

    /** 确认收到退货 → 触发退款。 */
    AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo);

    record ApplyCommand(String type, String reason, List<String> images, Long refundMinor) {
    }

    /**
     * 待商家处理的售后单数（工作台待办）。
     *
     * <p>只数 {@code APPLIED} —— 那是**球在商家手里**的唯一状态。
     * 把 REFUNDING（等买家寄回）也算进去的话，待办数会一直挂着一个商家做不了什么的数字，
     * 而挂久了整块待办就没人看了。
     */
    int merchantPendingCount(String merchantNo);
}
