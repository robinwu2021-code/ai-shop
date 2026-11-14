package ai.neargo.shop.pay.merchant.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_merchant_status_log（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：收款商户状态变更留痕（启用、切换资金路径、暂停、关闭）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_merchant_status_log")
public class PayMerchantStatusLog extends PayBaseEntity {

    /** 收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 原状态 */
    private String fromStatus;

    /** 新状态 */
    private String toStatus;

    /** 原因 */
    private String reason;

    /** 对应复核单 pay_approval.id */
    private Long payApprovalId;

    /** 操作人（审计字段） */
    private String operatorNo;

    /** 时间 */
    private LocalDateTime changedAt;
}
