package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.impl.ServiceSourceObservation;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real source bill aggregation over local MySQL; synthetic currency/receipt variants are owner
 * contract fixtures.
 */
class WorkOrderBillIT extends SourceFixture {
    @Test
    void canonicalReceiptAliasesCountOnceAndUnknownNeverBecomesZero() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            var original = sourceFacts.get("OP");
                            sourceFacts.put(
                                    "OP",
                                    payments(
                                            original,
                                            List.of(
                                                    original.payments().getFirst(),
                                                    original.payments().getFirst())));
                            var submission =
                                    context.getBean(ServiceSubmissionService.class)
                                            .submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    existing(root, root.taskNos().getFirst(), "OP"),
                                                    "BIND");
                            assertThat(submission.status()).isEqualTo("BOUND");
                            var bill =
                                    context.getBean(WorkOrderService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(bill.sources()).hasSize(1);
                            assertThat(bill.complete()).isTrue();
                            assertThat(bill.sources().getFirst().receivedMinor()).isEqualTo(1000);
                            assertThat(bill.sources().getFirst().receipts()).hasSize(1);
                            assertThat(bill.currencies().getFirst().dueMinor()).isEqualTo(1000);
                            sourceFacts.put(
                                    "OP",
                                    new CheckoutPort.CheckoutSourceFacts(
                                            original.operation(),
                                            original.orderNo(),
                                            original.subOrderNo(),
                                            original.buyerNo(),
                                            "E",
                                            "S",
                                            1,
                                            original.sourceHash(),
                                            null,
                                            "CNY",
                                            0,
                                            null,
                                            null,
                                            "UNKNOWN",
                                            List.of(),
                                            List.of(),
                                            "BLOCKED",
                                            Instant.now()));
                            var unknown =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(unknown.complete()).isFalse();
                            assertThat(unknown.sources().getFirst().dueMinor()).isNull();
                            assertThat(unknown.sources().getFirst().receivedMinor()).isNull();
                            assertThat(unknown.currencies().getFirst().dueMinor()).isNull();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isOne();
                            assertThat(prepares).hasValue(0);
                        });
    }

    @Test
    void partialAllocationShowsWholeSourceAndKeepsAggregateNullable() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 2);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "BIND");
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(bill.sources()).hasSize(1);
                            assertThat(bill.sources().getFirst().dueMinor()).isEqualTo(2000);
                            assertThat(bill.sources().getFirst().amountScope())
                                    .isEqualTo("WHOLE_SOURCE");
                            assertThat(bill.sources().getFirst().blockingReasons())
                                    .contains("SOURCE_AMOUNT_NOT_ATTRIBUTABLE");
                            assertThat(bill.complete()).isFalse();
                            assertThat(bill.currencies().getFirst().dueMinor()).isNull();
                        });
    }

    @Test
    void conflictingReceiptIdentityCannotCreateMoneyOrObservedReadiness() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            var original = sourceFacts.get("OP");
                            var receipt = original.payments().getFirst();
                            var conflict =
                                    new CheckoutPort.PaymentFact(
                                            receipt.paymentOperationNo(),
                                            receipt.paymentNo(),
                                            receipt.outTradeNo(),
                                            receipt.channel(),
                                            receipt.tradeNo(),
                                            receipt.status(),
                                            999,
                                            receipt.currency(),
                                            receipt.receiptNo(),
                                            receipt.observedAt());
                            sourceFacts.put("OP", payments(original, List.of(receipt, conflict)));
                            var submitted =
                                    context.getBean(ServiceSubmissionService.class)
                                            .submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    existing(root, root.taskNos().getFirst(), "OP"),
                                                    "BIND");
                            assertThat(submitted.status()).isEqualTo("SOURCE_PENDING");
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(bill.complete()).isFalse();
                            assertThat(bill.sources().getFirst().dueMinor()).isNull();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                        });
    }

    private CheckoutPort.CheckoutSourceFacts payments(
            CheckoutPort.CheckoutSourceFacts facts, List<CheckoutPort.PaymentFact> payments) {
        return new CheckoutPort.CheckoutSourceFacts(
                facts.operation(),
                facts.orderNo(),
                facts.subOrderNo(),
                facts.buyerNo(),
                facts.entityNo(),
                facts.storeNo(),
                facts.sourceRevision(),
                facts.sourceHash(),
                facts.orderStatus(),
                facts.currency(),
                facts.dueMinor(),
                facts.receivedMinor(),
                facts.outstandingMinor(),
                facts.financialState(),
                facts.lines(),
                payments,
                facts.executionState(),
                facts.observedAt());
    }

    @Test
    void blockedCurrentPairPreservesHistoricalMoneyAndReceipt() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "BIND");
                            var original = sourceFacts.get("OP");
                            sourceFacts.put(
                                    "OP",
                                    new CheckoutPort.CheckoutSourceFacts(
                                            original.operation(),
                                            original.orderNo(),
                                            original.subOrderNo(),
                                            original.buyerNo(),
                                            "E",
                                            "S",
                                            2,
                                            "b".repeat(64),
                                            "CLOSED",
                                            "CNY",
                                            1000,
                                            0L,
                                            1000L,
                                            "REFUNDED",
                                            List.of(
                                                    new CheckoutPort.SourceLine(
                                                            "LINE",
                                                            "ORIGINAL",
                                                            "G",
                                                            "SKU",
                                                            1,
                                                            0,
                                                            "COUNT",
                                                            1000)),
                                            original.payments(),
                                            "BLOCKED",
                                            Instant.now()));
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(bill.sources().getFirst().receivedMinor()).isEqualTo(1000);
                            assertThat(bill.sources().getFirst().receipts().getFirst().receiptNo())
                                    .isEqualTo("RC-OP");
                            assertThat(bill.sources().getFirst().observation().canStart())
                                    .isFalse();
                            assertThat(bill.complete()).isTrue();
                            var source = bill.sources().getFirst();
                            assertThat(source.complete()).isTrue();
                            assertThat(source.blockingReasons()).contains("SOURCE_OWNER_BLOCKED");
                            assertThat(source.refundedMinor()).isEqualTo(1000);
                            assertThat(source.netReceivedMinor()).isZero();
                            var totals = bill.currencies().getFirst();
                            assertThat(totals.complete()).isTrue();
                            assertThat(totals.dueMinor()).isEqualTo(1000);
                            assertThat(totals.receivedMinor()).isEqualTo(1000);
                            assertThat(totals.refundedMinor()).isEqualTo(1000);
                            assertThat(totals.netReceivedMinor()).isZero();
                            assertThat(totals.outstandingMinor()).isEqualTo(1000);
                        });
    }

    @Test
    void traceResolvesOriginalTaskAndSubmissionWithoutPurchasing() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            var submission =
                                    context.getBean(ServiceSubmissionService.class)
                                            .submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    existing(root, root.taskNos().getFirst(), "OP"),
                                                    "BIND");
                            var bill = context.getBean(WorkOrderBillService.class);
                            var fromTask = bill.getTrace("E", "S", "A", root.taskNos().getFirst());
                            var fromSubmission =
                                    bill.getTrace("E", "S", "A", submission.submissionNo());
                            assertThat(fromTask.workOrderNo()).isEqualTo(root.workOrderNo());
                            assertThat(fromSubmission.submissions()).hasSize(1);
                            assertThat(fromTask.bill().sources().getFirst().receipts()).hasSize(1);
                            assertThat(prepares).hasValue(0);
                        });
    }

    @Test
    void currencyBucketsStaySeparateWhenOneOwnerReadFails() {
        business()
                .run(
                        context -> {
                            var root = open(context, 2, "OPEN");
                            existingOwner("CNY", 1);
                            existingOwner("EUR", 1);
                            changeCurrency("EUR", "EUR");
                            var service = context.getBean(ServiceSubmissionService.class);
                            service.submitForWorkOrder(
                                    "E",
                                    "S",
                                    "A",
                                    root.workOrderNo(),
                                    existing(root, root.taskNos().getFirst(), "CNY"),
                                    "CNY");
                            var second = existing(root, root.taskNos().getLast(), "EUR");
                            service.submitForWorkOrder(
                                    "E",
                                    "S",
                                    "A",
                                    root.workOrderNo(),
                                    new SubmitServicesReq(
                                            1, 1, second.tasks(), List.of(), second.source()),
                                    "EUR");
                            var bills = context.getBean(WorkOrderBillService.class);
                            var full = bills.getBill("E", "S", "A", root.workOrderNo());
                            assertThat(full.currencies()).hasSize(2);
                            assertThat(full.currencies())
                                    .allSatisfy(
                                            currency ->
                                                    assertThat(currency.dueMinor())
                                                            .isEqualTo(1000));
                            failSourceOperation = "EUR";
                            var partial = bills.getBill("E", "S", "A", root.workOrderNo());
                            assertThat(partial.complete()).isFalse();
                            assertThat(
                                            partial.currencies().stream()
                                                    .filter(c -> c.currency().equals("CNY"))
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .dueMinor())
                                    .isEqualTo(1000);
                            assertThat(
                                            partial.currencies().stream()
                                                    .filter(c -> c.currency().equals("EUR"))
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .dueMinor())
                                    .isNull();
                        });
    }

    @Test
    void sourceManifestTraversesAllPagesAndBoundsChangingSnapshotRetry() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "BIND");
                            // Controlled local manifest aliases exercise keyset paging, not real
                            // acceptance of 101 purchases.
                            for (int index = 0; index < 100; index++) {
                                String namespace =
                                        IdempotencyScope.requestHash(
                                                "page", Integer.toString(index));
                                sql.update(
                                        "INSERT INTO"
                                            + " bty_service_submission(tenant_no,entity_no,store_no,"
                                            + "work_order_no,submission_no,"
                                            + "action,request_key_hash,payload_hash,frozen_command,status,"
                                            + "created_at,updated_at)"
                                            + " SELECT"
                                            + " tenant_no,entity_no,store_no,work_order_no,?,action,?,payload_hash,"
                                            + "JSON_SET(frozen_command,'$.namespaceHash',?),status,NOW(),NOW()"
                                            + " FROM bty_service_submission WHERE"
                                            + " action='SUBMIT_SOURCE' ORDER BY id LIMIT 1",
                                        "PAGE" + index,
                                        namespace,
                                        namespace);
                            }
                            var bills = context.getBean(WorkOrderBillService.class);
                            assertThat(
                                            bills.getTrace("E", "S", "A", root.workOrderNo())
                                                    .submissions())
                                    .hasSize(101);
                            var calls = new AtomicInteger();
                            sourceRead =
                                    lookup -> {
                                        calls.incrementAndGet();
                                        sql.update(
                                                "UPDATE bty_work_order SET version=version+1 WHERE"
                                                        + " work_order_no=?",
                                                root.workOrderNo());
                                    };
                            var changed = bills.getBill("E", "S", "A", root.workOrderNo());
                            assertThat(calls).hasValue(2);
                            assertThat(changed.complete()).isFalse();
                            assertThat(changed.blockingReasons())
                                    .contains("LOCAL_SNAPSHOT_CHANGED");
                            assertThat(changed.currencies().getFirst().dueMinor()).isNull();
                        });
    }

    private void changeCurrency(String operation, String currency) {
        var quote = quotes.get(operation);
        quotes.put(
                operation,
                new CheckoutPort.CheckoutQuote(
                        quote.operation(),
                        quote.quoteNo(),
                        quote.quoteRevision(),
                        quote.quoteHash(),
                        quote.expiresAt(),
                        quote.buyerNo(),
                        quote.entityNo(),
                        quote.storeNo(),
                        currency,
                        quote.lines(),
                        quote.goodsMinor(),
                        quote.discountMinor(),
                        quote.dueMinor(),
                        quote.payMode(),
                        quote.fulfillment(),
                        quote.fulfillmentAuthority(),
                        quote.promotions(),
                        quote.discounts(),
                        quote.phase(),
                        quote.operationRevision()));
        var original = sourceFacts.get(operation);
        var receipt = original.payments().getFirst();
        var changed =
                new CheckoutPort.PaymentFact(
                        receipt.paymentOperationNo(),
                        receipt.paymentNo(),
                        receipt.outTradeNo(),
                        receipt.channel(),
                        receipt.tradeNo(),
                        receipt.status(),
                        receipt.amountMinor(),
                        currency,
                        receipt.receiptNo(),
                        receipt.observedAt());
        sourceFacts.put(
                operation,
                new CheckoutPort.CheckoutSourceFacts(
                        original.operation(),
                        original.orderNo(),
                        original.subOrderNo(),
                        original.buyerNo(),
                        "E",
                        "S",
                        original.sourceRevision(),
                        original.sourceHash(),
                        original.orderStatus(),
                        currency,
                        original.dueMinor(),
                        original.receivedMinor(),
                        original.outstandingMinor(),
                        original.financialState(),
                        original.lines(),
                        List.of(changed),
                        original.executionState(),
                        original.observedAt()));
    }

    @Test
    void aggregateOverflowNeverReportsACompleteBill() {
        business()
                .run(
                        context -> {
                            var root = open(context, 2, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            int index = 0;
                            for (String operation : List.of("BIG1", "BIG2")) {
                                existingOwner(operation, 1);
                                var old = sourceFacts.get(operation);
                                long amount = Long.MAX_VALUE / 2 + 1;
                                sourceFacts.put(
                                        operation,
                                        new CheckoutPort.CheckoutSourceFacts(
                                                old.operation(),
                                                old.orderNo(),
                                                old.subOrderNo(),
                                                old.buyerNo(),
                                                "E",
                                                "S",
                                                1,
                                                old.sourceHash(),
                                                "PAID",
                                                "CNY",
                                                amount,
                                                amount,
                                                0L,
                                                "PAID",
                                                List.of(
                                                        new CheckoutPort.SourceLine(
                                                                "LINE",
                                                                "ORIGINAL",
                                                                "G",
                                                                "SKU",
                                                                1,
                                                                1,
                                                                "COUNT",
                                                                amount)),
                                                List.of(
                                                        new CheckoutPort.PaymentFact(
                                                                "PO-" + operation,
                                                                "PAY-" + operation,
                                                                "OUT-" + operation,
                                                                "CASH",
                                                                "TRADE-" + operation,
                                                                "SUCCESS",
                                                                amount,
                                                                "CNY",
                                                                "RC-" + operation,
                                                                Instant.now())),
                                                "NOT_STARTED",
                                                Instant.now()));
                                var selected = existing(root, root.taskNos().get(index), operation);
                                service.submitForWorkOrder(
                                        "E",
                                        "S",
                                        "A",
                                        root.workOrderNo(),
                                        new SubmitServicesReq(
                                                index,
                                                1,
                                                selected.tasks(),
                                                List.of(),
                                                selected.source()),
                                        operation);
                                index++;
                            }
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            assertThat(bill.complete()).isFalse();
                            assertThat(bill.currencies().getFirst().complete()).isFalse();
                            assertThat(bill.currencies().getFirst().dueMinor()).isNull();
                            assertThat(bill.blockingReasons())
                                    .contains("CURRENCY_AGGREGATE_INCOMPLETE");
                        });
    }

    @Test
    void ownerAuthorizationFailureIsNotPartialFinancialAvailability() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "BIND");
                            sourceRead =
                                    lookup -> {
                                        throw BizException.of(ErrorCode.UNAUTHORIZED);
                                    };
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            context.getBean(WorkOrderBillService.class)
                                                    .getBill("E", "S", "A", root.workOrderNo()));
                        });
    }

    @Test
    void sourceFailureRetainsHistoricalPairAndBoundLineWithoutPretendingReady() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "KEY");
                            failSourceOperation = "OP";
                            var historical =
                                    service.getSubmission("E", "S", "A", accepted.submissionNo());
                            assertThat(historical.source()).isNotNull();
                            assertThat(historical.source().state()).isEqualTo("UNKNOWN");
                            assertThat(historical.source().sourceRevision()).isEqualTo(1);
                            assertThat(historical.source().lastVerifiedRevision()).isEqualTo(1);
                            assertThat(historical.subOrderNo()).isEqualTo("SUB-OP");
                            assertThat(historical.tasks().getFirst().sourceLineNo())
                                    .isEqualTo("LINE");
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            var source = bill.sources().getFirst();
                            assertThat(source.observation()).isNotNull();
                            assertThat(source.observation().state()).isEqualTo("UNKNOWN");
                            assertThat(source.observation().lastVerifiedRevision()).isEqualTo(1);
                            assertThat(source.receivedMinor()).isNull();
                            assertThat(source.complete()).isFalse();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT allocated_qty FROM"
                                                            + " bty_source_allocation_guard",
                                                    BigDecimal.class))
                                    .isEqualByComparingTo("1");
                        });
    }

    @Test
    void negativeCurrencyMismatchAndReceiptOverflowNeverCreateKnownMoney() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "BIND");
                            var facts = sourceFacts.get("OP");
                            var receipt = facts.payments().getFirst();
                            var cases =
                                    List.of(
                                            List.of(
                                                    new CheckoutPort.PaymentFact(
                                                            "PO",
                                                            "PAY",
                                                            "OUT",
                                                            "CASH",
                                                            "TRADE",
                                                            "SUCCESS",
                                                            -1,
                                                            "CNY",
                                                            "RC",
                                                            Instant.now())),
                                            List.of(
                                                    new CheckoutPort.PaymentFact(
                                                            "PO",
                                                            "PAY",
                                                            "OUT",
                                                            "CASH",
                                                            "TRADE",
                                                            "SUCCESS",
                                                            1000,
                                                            "EUR",
                                                            "RC",
                                                            Instant.now())),
                                            List.of(
                                                    new CheckoutPort.PaymentFact(
                                                            "PO",
                                                            "PAY",
                                                            "OUT",
                                                            "CASH",
                                                            "TRADE",
                                                            "SUCCESS",
                                                            Long.MAX_VALUE,
                                                            "CNY",
                                                            "RC1",
                                                            Instant.now()),
                                                    receipt));
                            for (var invalid : cases) {
                                sourceFacts.put("OP", payments(facts, invalid));
                                var bill =
                                        context.getBean(WorkOrderBillService.class)
                                                .getBill("E", "S", "A", root.workOrderNo());
                                assertThat(bill.complete()).isFalse();
                                assertThat(bill.sources().getFirst().receivedMinor()).isNull();
                                assertThat(bill.currencies().getFirst().receivedMinor()).isNull();
                            }
                        });
    }

    @Test
    void ownerQuoteFailureInvalidatesEarlierSourceTicketAndRetainsHistory() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "KEY");
                            long before =
                                    sql.queryForObject(
                                            "SELECT observation_epoch FROM bty_source_observation",
                                            Long.class);
                            failQuoteOperation = "OP";
                            var historical =
                                    service.getSubmission("E", "S", "A", accepted.submissionNo());
                            assertThat(historical.source().state()).isEqualTo("UNKNOWN");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT state FROM bty_source_observation",
                                                    String.class))
                                    .isEqualTo("UNKNOWN");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT observation_epoch FROM"
                                                            + " bty_source_observation",
                                                    Long.class))
                                    .isGreaterThan(before);
                            failQuoteOperation = null;
                            service.getSubmission("E", "S", "A", accepted.submissionNo());
                            var entered = new CountDownLatch(1);
                            var release = new CountDownLatch(1);
                            sourceRead =
                                    lookup -> {
                                        entered.countDown();
                                        try {
                                            assertThat(release.await(15, TimeUnit.SECONDS))
                                                    .isTrue();
                                        } catch (InterruptedException failure) {
                                            throw new AssertionError(failure);
                                        }
                                    };
                            try (var executor = Executors.newSingleThreadExecutor()) {
                                var future =
                                        executor.submit(
                                                () ->
                                                        context.getBean(
                                                                        ServiceSourceObservation
                                                                                .class)
                                                                .observe(
                                                                        new ServiceSourceObservation
                                                                                .Canonical(
                                                                                "E", "S", "USR1",
                                                                                "OP", "ORD-OP",
                                                                                "SUB-OP"),
                                                                        "A"));
                                try {
                                    assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                                    failQuoteOperation = "OP";
                                    var bill =
                                            context.getBean(WorkOrderBillService.class)
                                                    .getBill("E", "S", "A", root.workOrderNo());
                                    assertThat(bill.sources().getFirst().receivedMinor()).isNull();
                                    assertThat(
                                                    bill.sources()
                                                            .getFirst()
                                                            .observation()
                                                            .lastVerifiedRevision())
                                            .isEqualTo(1);
                                } catch (InterruptedException failure) {
                                    throw new AssertionError(failure);
                                } finally {
                                    release.countDown();
                                }
                                try {
                                    assertThat(future.get(15, TimeUnit.SECONDS).ready()).isFalse();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT state FROM bty_source_observation",
                                                    String.class))
                                    .isEqualTo("UNKNOWN");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT source_revision FROM"
                                                            + " bty_source_observation",
                                                    Long.class))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void missingOwnerPreservesBoundSourceHistoryWithUnknownMoney() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "KEY");
                            var factory =
                                    (DefaultListableBeanFactory)
                                            context.getSourceApplicationContext().getBeanFactory();
                            for (String name : context.getBeanNamesForType(CheckoutPort.class)) {
                                factory.removeBeanDefinition(name);
                            }
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            var source = bill.sources().getFirst();
                            assertThat(source.orderNo()).isEqualTo("ORD-OP");
                            assertThat(source.sourceNo()).isEqualTo("SUB-OP");
                            assertThat(source.observation().state()).isEqualTo("UNKNOWN");
                            assertThat(source.observation().lastVerifiedRevision()).isEqualTo(1);
                            assertThat(source.dueMinor()).isNull();
                            assertThat(source.receivedMinor()).isNull();
                            assertThat(source.complete()).isFalse();
                            assertThat(source.tasks().getFirst().sourceLineNo()).isEqualTo("LINE");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT state FROM bty_source_observation",
                                                    String.class))
                                    .isEqualTo("UNKNOWN");
                        });
    }

    @Test
    void nonCreatedOwnerPreservesBoundSourceHistoryWithUnknownMoney() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            context.getBean(ServiceSubmissionService.class)
                                    .submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(root, root.taskNos().getFirst(), "OP"),
                                            "KEY");
                            created.remove("OP");
                            var bill =
                                    context.getBean(WorkOrderBillService.class)
                                            .getBill("E", "S", "A", root.workOrderNo());
                            var source = bill.sources().getFirst();
                            assertThat(source.orderNo()).isEqualTo("ORD-OP");
                            assertThat(source.sourceNo()).isEqualTo("SUB-OP");
                            assertThat(source.observation().state()).isEqualTo("UNKNOWN");
                            assertThat(source.observation().lastVerifiedHash())
                                    .isEqualTo("a".repeat(64));
                            assertThat(source.dueMinor()).isNull();
                            assertThat(source.receivedMinor()).isNull();
                            assertThat(bill.complete()).isFalse();
                            assertThat(bill.currencies().getFirst().netReceivedMinor()).isNull();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT state FROM bty_source_observation",
                                                    String.class))
                                    .isEqualTo("UNKNOWN");
                        });
    }
}
