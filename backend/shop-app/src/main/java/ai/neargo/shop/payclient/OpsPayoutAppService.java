package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.PayoutService;

import java.util.List;

/** 运营端 · 放款记录（V391）。审计与操作人在这一层取，域服务不碰会话 */
public interface OpsPayoutAppService {

    /** @param status 空 = 全部；{@code PENDING} 待导出、{@code EXPORTED} 已导待登记 */
    List<PayoutService.PayoutVO> list(String status, String entityNo);

    /** 登记凭证。写 critical 审计：这是钱出账的登记 */
    PayoutService.PayoutVO markPaid(String payoutNo, String paymentRef);

    /** 打款失败或退回。原因必填 */
    PayoutService.PayoutVO markFailed(String payoutNo, String reason);
}
