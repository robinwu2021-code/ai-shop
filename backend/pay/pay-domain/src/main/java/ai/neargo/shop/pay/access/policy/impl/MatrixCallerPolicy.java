package ai.neargo.shop.pay.access.policy.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.access.policy.CapabilityMatrix;
import ai.neargo.shop.pay.channel.api.CallerType;
import org.springframework.stereotype.Component;

/** 矩阵 ∩ 门店范围；退款另要求有商家操作员 —— 无人可追责的退款一笔都不放 */
@Component
public class MatrixCallerPolicy implements CallerPolicy {

    @Override
    public void check(PayCaller caller, PayAction action, Long storeId) {
        if (caller == null || !CapabilityMatrix.allows(caller.type(), action)) {
            throw BizException.of(ErrorCode.PAY_FORBIDDEN);
        }
        // 运营端是平台身份，不按门店圈；其余一律只能动自己范围内的门店
        if (caller.type() != CallerType.OPS && !caller.covers(storeId)) {
            throw BizException.of(ErrorCode.PAY_FORBIDDEN);
        }
        if (action == PayAction.REQUEST_REFUND && !caller.hasOperator()) {
            throw BizException.of(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
        }
    }
}
