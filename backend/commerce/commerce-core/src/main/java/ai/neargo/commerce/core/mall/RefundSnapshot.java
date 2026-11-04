package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.VerifiedPaymentFact;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.*;

import java.time.Instant;
import java.util.*;

public final class RefundSnapshot {
    private RefundSnapshot() {}

    public record Payment(String attemptNo, VerifiedPaymentFact fact) {}

    public record Source(
            Period period,
            long version,
            String componentNo,
            long cashLimit,
            long creditLimit,
            long usedDuration,
            boolean manual,
            List<CreditSlice> creditSlices) {
        public Source {
            creditSlices = List.copyOf(creditSlices);
        }
    }

    public record PendingTask(
            String taskNo,
            String componentNo,
            int sequence,
            long version,
            String requestHash,
            long cash,
            long credit,
            boolean manual) {}

    public record Preview(
            String refundNo,
            String orderNo,
            StoreRef store,
            String currency,
            String buyerNo,
            long createdAt,
            long expiresAt,
            List<Source> sources,
            Map<String, List<AccessSegment>> history,
            Payment payment,
            boolean late,
            String digest,
            String previousRefund,
            List<PendingTask> pendingTasks) {
        public Preview(
                String refundNo,
                String orderNo,
                StoreRef store,
                String currency,
                String buyerNo,
                long createdAt,
                long expiresAt,
                List<Source> sources,
                Map<String, List<AccessSegment>> history,
                Payment payment,
                boolean late,
                String digest,
                String previousRefund) {
            this(
                    refundNo,
                    orderNo,
                    store,
                    currency,
                    buyerNo,
                    createdAt,
                    expiresAt,
                    sources,
                    history,
                    payment,
                    late,
                    digest,
                    previousRefund,
                    List.of());
        }

        public Preview {
            pendingTasks = pendingTasks == null ? List.of() : List.copyOf(pendingTasks);
            if (previousRefund == null) previousRefund = "NONE";
            sources = List.copyOf(sources);
            history = Map.copyOf(history);
        }

        public long cashLimit() {
            return late
                    ? payment.fact().paid().minor()
                    : Math.addExact(
                            sources.stream().mapToLong(Source::cashLimit).reduce(0, Math::addExact),
                            pendingTasks.stream()
                                    .mapToLong(PendingTask::cash)
                                    .reduce(0, Math::addExact));
        }

        public long creditLimit() {
            return sources.stream().mapToLong(Source::creditLimit).reduce(0, Math::addExact);
        }

        public RefundPreviewView view() {
            return new RefundPreviewView(
                    refundNo,
                    orderNo,
                    new Money(currency, cashLimit()),
                    new Money(currency, creditLimit()),
                    sources.stream().anyMatch(Source::manual)
                            || pendingTasks.stream().anyMatch(PendingTask::manual),
                    sources.stream().map(x -> x.period().no()).toList(),
                    Instant.ofEpochMilli(expiresAt),
                    digest);
        }
    }

    public record ReviewQuote(String reviewQuoteNo, String requestDigest, Preview preview) {
        public RefundReviewQuoteView view() {
            return new RefundReviewQuoteView(reviewQuoteNo, requestDigest, preview.view());
        }
    }

    public record State(
            Preview preview,
            String status,
            long approvedCash,
            long restoredCredit,
            String reviewer,
            String reviewEvidence,
            PaymentRefundCommand command,
            VerifiedRefundFact fact,
            Long reviewedAt,
            String requestDigest,
            String reviewQuoteNo) {
        public State(Preview preview, String status, long approvedCash, long restoredCredit,
                String reviewer, String reviewEvidence, PaymentRefundCommand command,
                VerifiedRefundFact fact, Long reviewedAt) {
            this(preview, status, approvedCash, restoredCredit, reviewer, reviewEvidence, command,
                    fact, reviewedAt, preview.digest(), null);
        }
        public State withReview(ReviewQuote quote) {
            return new State(quote.preview(), status, approvedCash, restoredCredit, reviewer,
                    reviewEvidence, command, fact, reviewedAt, quote.requestDigest(), quote.reviewQuoteNo());
        }
        public State inheritReview(State previous) {
            return new State(preview, status, approvedCash, restoredCredit, reviewer, reviewEvidence,
                    command, fact, reviewedAt == null ? previous.reviewedAt() : reviewedAt,
                    previous.requestDigest(), previous.reviewQuoteNo());
        }
        public State(
                Preview preview,
                String status,
                long approvedCash,
                long restoredCredit,
                String reviewer,
                String reviewEvidence,
                PaymentRefundCommand command,
                VerifiedRefundFact fact) {
            this(
                    preview,
                    status,
                    approvedCash,
                    restoredCredit,
                    reviewer,
                    reviewEvidence,
                    command,
                    fact,
                    null);
        }

        public RefundView view() {
            return new RefundView(
                    preview.refundNo(),
                    preview.orderNo(),
                    switch (status) {
                        case "SUCCEEDED" -> AsyncStatus.SUCCEEDED;
                        case "REJECTED", "DECLINED" -> AsyncStatus.FAILED;
                        case "UNKNOWN", "DISPATCHING" -> AsyncStatus.UNKNOWN;
                        default -> AsyncStatus.PENDING;
                    },
                    new Money(preview.currency(), approvedCash),
                    new Money(preview.currency(), restoredCredit),
                    status, requestDigest, reviewQuoteNo);
        }
    }
}
