package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.user.QualificationPort;

import org.springframework.stereotype.Component;

/** Reject unsupported economic choices before the shared pricing or stock owners have effects. */
@Component
public class ManualServicePurchasePolicy {
    private final QualificationPort qualifications;

    public ManualServicePurchasePolicy(QualificationPort qualifications) {
        this.qualifications = qualifications;
    }

    /**
     * Business eligibility remains required independently from payment-method/channel
     * configuration.
     */
    public void requireBusiness(CheckoutScope scope) {
        if (!qualifications.hasValidQualification(
                scope.entityNo(), QualificationPort.BUSINESS_LICENSE)) {
            throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
        }
    }

    public void requireSupported(PrepareCheckoutReq request) {
        if (request == null
                || !"OFFLINE".equals(request.payMode())
                || request.couponNo() != null
                || !request.activityChoices().isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }
}
