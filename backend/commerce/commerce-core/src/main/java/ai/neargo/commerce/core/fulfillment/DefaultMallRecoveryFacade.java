package ai.neargo.commerce.core.fulfillment;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import java.util.Objects;

/** Bounded rotating scan. Every IO coordinator runs after the identifier-read transaction ends. */
public final class DefaultMallRecoveryFacade implements MallRecoveryFacade {
    private final RecoveryRepository repository;
    private final CommerceUnitOfWork uow;
    private final PaymentFacade payments;
    private final RefundFacade refunds;

    public DefaultMallRecoveryFacade(
            RecoveryRepository repository,
            CommerceUnitOfWork uow,
            PaymentFacade payments,
            RefundFacade refunds) {
        this.repository = Objects.requireNonNull(repository);
        this.uow = Objects.requireNonNull(uow);
        this.payments = Objects.requireNonNull(payments);
        this.refunds = refunds;
    }

    public Result recover(Batch batch) {
        uow.requireOutsideTransaction();
        if (batch == null || batch.afterOrderId() < 0 || batch.limit() < 1 || batch.limit() > 500)
            throw new IllegalArgumentException("invalid recovery batch");
        var candidates = uow.required(() -> repository.orders(batch.afterOrderId(), batch.limit()));
        int failed = 0;
        for (var candidate : candidates) {
            var query = new FulfillmentQuery(candidate.tenantNo(), candidate.orderNo());
            boolean failure = false;
            FulfillmentResult state = null;
            try {
                state = payments.refresh(query);
            } catch (RuntimeException retry) {
                failure = true;
            }
            if (refunds != null) {
                try {
                    var pending =
                            uow.required(
                                    () ->
                                            repository.pendingRefunds(
                                                    candidate.tenantNo(),
                                                    candidate.orderNo(),
                                                    100));
                    for (String refund : pending) {
                        try {
                            refunds.recover(
                                    new RefundQuery(
                                            candidate.tenantNo(), candidate.orderNo(), refund));
                        } catch (RuntimeException retry) {
                            failure = true;
                        }
                    }
                    if (state != null && state.digitalStatus() == AsyncStatus.FAILED)
                        refunds.recoverLatePayment(query);
                } catch (RuntimeException retry) {
                    failure = true;
                }
            }
            // Required after late-payment compensation to resolve original inventory release
            // fences.
            try {
                payments.recover(query);
            } catch (RuntimeException retry) {
                failure = true;
            }
            if (failure) failed++;
        }
        return new Result(
                candidates.isEmpty() ? 0 : candidates.getLast().id(),
                candidates.size(),
                failed,
                candidates.size() < batch.limit());
    }
}
