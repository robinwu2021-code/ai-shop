package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.impl.ServiceSourceBinding;
import ai.neargo.shop.beauty.service.impl.ServiceSourceObservation;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit local submission against real beauty scope; external checkout is a counted fixture. */
class ServiceSubmissionIT extends SourceFixture {
    @Test
    void openDraftDoesNotPurchaseAndExplicitSelectionIsExact() {
        business()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var root = open(context, 2, "OPEN");
                            assertThat(prepares).hasValue(0);
                            var result =
                                    context.getBean(WorkOrderService.class)
                                            .submitServices(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    selection(root, root.taskNos().getFirst()),
                                                    "SUBMIT");
                            assertThat(result.quote())
                                    .as("explicit selected source gets a real owner quote")
                                    .isNotNull();
                            assertThat(prepares).hasValue(1);
                            assertThat(prepared.lines()).hasSize(1);
                            assertThat(prepared.lines().getFirst().quantity()).isOne();
                            assertThat(result.tasks()).hasSize(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_service_submission"
                                                            + " WHERE action='OPEN'",
                                                    Integer.class))
                                    .isOne();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_service_submission"
                                                            + " WHERE action IN"
                                                            + " ('SUBMIT_SOURCE','ADD_SOURCE')",
                                                    Integer.class))
                                    .isOne();
                        });
    }

    @Test
    void originalNamespaceAndChangedPayloadArePermanent() {
        business()
                .run(
                        context -> {
                            var root = open(context, 2, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var request = selection(root, root.taskNos().getFirst());
                            var first =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "KEY");
                            assertThat(
                                            service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            request,
                                                            "KEY")
                                                    .submissionNo())
                                    .isEqualTo(first.submissionNo());
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    selection(root, root.taskNos().getLast()),
                                                    "KEY"));
                            login(LoginUser.merchantByUser("A", "another namespace"));
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    request,
                                                    "KEY"));
                            assertThat(prepares).hasValue(1);
                        });
    }

    @Test
    void prepareResponseLossRetainsOriginalOperationAfterRestart() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            losePrepareResponse = true;
                            var pending =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            selection(
                                                                    root,
                                                                    root.taskNos().getFirst()),
                                                            "KEY"));
                            assertThat(pending.phase()).isEqualTo("PREPARE_UNKNOWN");
                            losePrepareResponse = false;
                            login(LoginUser.merchant("B", "current manager"));
                            var reconstructed =
                                    context.getAutowireCapableBeanFactory()
                                            .createBean(ServiceSubmissionServiceImpl.class);
                            var recovered =
                                    reconstructed.recoverPending(
                                            "E", "S", "B", pending.submissionNo());
                            assertThat(recovered.checkoutOperationNo())
                                    .isEqualTo(pending.checkoutOperationNo());
                            assertThat(recovered.quote()).isNotNull();
                            assertThat(prepares).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT frozen_command FROM"
                                                            + " bty_service_submission WHERE"
                                                            + " action='SUBMIT_SOURCE'",
                                                    String.class))
                                    .doesNotContain(customerReference);
                        });
    }

    @Test
    void unacceptedPrepareNeedsOriginalRequestNotImpersonation() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            failPrepareBeforeAccept = true;
                            var request = selection(root, root.taskNos().getFirst());
                            var pending =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            request,
                                                            "KEY"));
                            login(LoginUser.merchant("B", "current manager"));
                            var recovered =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.recoverPending(
                                                            "E", "S", "B", pending.submissionNo()));
                            assertThat(recovered.phase())
                                    .isEqualTo("REQUIRE_ORIGINAL_PREPARE_REQUEST");
                            assertThat(prepares).hasValue(1);
                            failPrepareBeforeAccept = false;
                            login(LoginUser.merchant("A", "original manager"));
                            var resumed =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "KEY");
                            assertThat(resumed.submissionNo()).isEqualTo(pending.submissionNo());
                            assertThat(resumed.quote()).isNotNull();
                            assertThat(prepares).hasValue(2);
                        });
    }

    @Test
    void boundResponseLossReplaysWithoutAllocatingAgain() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            selection(root, root.taskNos().getFirst()),
                                            "KEY");
                            settled(accepted.checkoutOperationNo(), 1);
                            Assertions.assertThrows(
                                    UncheckedIOException.class,
                                    () -> {
                                        service.recoverPending(
                                                "E", "S", "A", accepted.submissionNo());
                                        assertThat(
                                                        sql.queryForObject(
                                                                "SELECT status FROM"
                                                                        + " bty_service_submission"
                                                                        + " WHERE submission_no=?",
                                                                String.class,
                                                                accepted.submissionNo()))
                                                .isEqualTo("BOUND");
                                        throw new UncheckedIOException(
                                                new IOException(
                                                        "response lost after local commit"));
                                    });
                            var reconstructed =
                                    context.getAutowireCapableBeanFactory()
                                            .createBean(ServiceSubmissionServiceImpl.class);
                            var replay =
                                    reconstructed.recoverPending(
                                            "E", "S", "A", accepted.submissionNo());
                            assertThat(replay.status()).isEqualTo("BOUND");
                            assertThat(replay.tasks().getFirst().sourceLineNo()).isEqualTo("LINE");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isOne();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT allocated_qty FROM"
                                                            + " bty_source_allocation_guard",
                                                    BigDecimal.class))
                                    .isEqualByComparingTo("1");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT binding_revision FROM bty_service_task",
                                                    Long.class))
                                    .isEqualTo(1);
                            assertThat(prepares).hasValue(1);
                        });
    }

    @Test
    void sameSourceAcrossWorkOrdersHasOneQuantityWinner() {
        business()
                .run(
                        context -> {
                            var first = open(context, 1, "OPEN1");
                            var second = open(context, 1, "OPEN2");
                            existingOwner("EXISTING", 1);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var start = new CountDownLatch(1);
                            try (var executor = Executors.newFixedThreadPool(2)) {
                                var futures =
                                        List.of(first, second).stream()
                                                .map(
                                                        root ->
                                                                executor.submit(
                                                                        () -> {
                                                                            login(
                                                                                    LoginUser
                                                                                            .merchant(
                                                                                                    "A",
                                                                                                    "operator"));
                                                                            start.await();
                                                                            String taskNo =
                                                                                    root.taskNos()
                                                                                            .getFirst();
                                                                            try {
                                                                                return service.submitForWorkOrder(
                                                                                                "E",
                                                                                                "S",
                                                                                                "A",
                                                                                                root
                                                                                                        .workOrderNo(),
                                                                                                existing(
                                                                                                        root,
                                                                                                        taskNo,
                                                                                                        "EXISTING"),
                                                                                                root
                                                                                                        .workOrderNo())
                                                                                        .status();
                                                                            } catch (
                                                                                    BizException
                                                                                            conflict) {
                                                                                return "CONFLICT";
                                                                            } finally {
                                                                                SecurityContextHolder
                                                                                        .clearContext();
                                                                            }
                                                                        }))
                                                .toList();
                                start.countDown();
                                int winners = 0;
                                for (var future : futures) {
                                    try {
                                        if ("BOUND".equals(future.get(20, TimeUnit.SECONDS))) {
                                            winners++;
                                        }
                                    } catch (Exception failure) {
                                        throw new AssertionError(failure);
                                    }
                                }
                                assertThat(winners).isOne();
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT COUNT(*) FROM bty_trade_binding",
                                                        Integer.class))
                                        .isOne();
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT allocated_qty FROM"
                                                                + " bty_source_allocation_guard",
                                                        BigDecimal.class))
                                        .isEqualByComparingTo("1");
                            }
                        });
    }

    @Test
    void currentGrantAndFullParentChainAreRequired() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var request = selection(root, root.taskNos().getFirst());
                            sourceAllowed = false;
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    request,
                                                    "KEY"));
                            sourceAllowed = true;
                            for (var table : List.of("bty_work_order", "bty_service_task")) {
                                sql.update("UPDATE " + table + " SET deleted=1");
                                Assertions.assertThrows(
                                        BizException.class,
                                        () ->
                                                service.submitForWorkOrder(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        root.workOrderNo(),
                                                        request,
                                                        "KEY"));
                                sql.update("UPDATE " + table + " SET deleted=0");
                            }
                            for (var table :
                                    List.of("bty_work_order_context", "bty_reception_operation")) {
                                sql.update("UPDATE " + table + " SET store_no='OTHER'");
                                Assertions.assertThrows(
                                        BizException.class,
                                        () ->
                                                service.submitForWorkOrder(
                                                        "E",
                                                        "S",
                                                        "A",
                                                        root.workOrderNo(),
                                                        request,
                                                        "KEY"));
                                sql.update("UPDATE " + table + " SET store_no='S'");
                            }
                            assertThat(prepares).hasValue(0);
                            assertThat(
                                            service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            request,
                                                            "KEY")
                                                    .quote())
                                    .isNotNull();
                        });
    }

    @Test
    void addedScopePreservesOriginalAndOldReceiptCannotBind() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            String original =
                                    sql.queryForObject(
                                            "SELECT frozen_scope FROM bty_work_order_context",
                                            String.class);
                            String definition =
                                    sql.queryForObject(
                                            "SELECT definition_version_no FROM bty_service_task",
                                            String.class);
                            var old =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            selection(root, root.taskNos().getFirst()),
                                            "OLD");
                            var addition =
                                    new SubmitServicesReq(
                                            0,
                                            1,
                                            List.of(),
                                            List.of(
                                                    new SubmitServicesReq.AddedItem(
                                                            "ADD", "SKU", definition, 1)),
                                            selection(root, root.taskNos().getFirst()).source());
                            var added =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            addition,
                                                            "ADD"));
                            assertThat(added.originalScopeRevision()).isOne();
                            assertThat(added.targetScopeRevision()).isEqualTo(2);
                            assertThat(prepared.lines().getFirst().quantity()).isOne();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_service_task",
                                                    Integer.class))
                                    .isEqualTo(2);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT frozen_scope FROM"
                                                            + " bty_work_order_context",
                                                    String.class))
                                    .isEqualTo(original);
                            settled(old.checkoutOperationNo(), 1);
                            var stale = service.recoverPending("E", "S", "A", old.submissionNo());
                            assertThat(stale.phase()).isEqualTo("SCOPE_CHANGED");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                            settled(added.checkoutOperationNo(), 1);
                            var bound = service.recoverPending("E", "S", "A", added.submissionNo());
                            assertThat(bound.status()).isEqualTo("BOUND");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT scope_revision FROM bty_work_order",
                                                    Long.class))
                                    .isEqualTo(2);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT binding_revision FROM bty_service_task"
                                                            + " WHERE task_no=?",
                                                    Long.class,
                                                    root.taskNos().getFirst()))
                                    .isZero();
                            assertThat(
                                            service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            addition,
                                                            "ADD")
                                                    .submissionNo())
                                    .isEqualTo(added.submissionNo());
                            assertThat(prepares).hasValue(2);
                        });
    }

    @Test
    void refundCancellationAndUnknownNeverReleaseHistory() {
        business()
                .run(
                        context -> {
                            var first = open(context, 1, "OPEN1");
                            var second = open(context, 1, "OPEN2");
                            existingOwner("EXISTING", 2);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var bound =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            first.workOrderNo(),
                                            existing(first, first.taskNos().getFirst(), "EXISTING"),
                                            "BIND1");
                            assertThat(bound.status()).isEqualTo("BOUND");
                            var original = sourceFacts.get("EXISTING");
                            sourceFacts.put(
                                    "EXISTING",
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
                                            2000,
                                            0L,
                                            0L,
                                            "REFUNDED",
                                            List.of(
                                                    new CheckoutPort.SourceLine(
                                                            "LINE",
                                                            "ORIGINAL",
                                                            "G",
                                                            "SKU",
                                                            2,
                                                            0,
                                                            "COUNT",
                                                            2000)),
                                            original.payments(),
                                            "BLOCKED",
                                            Instant.now()));
                            var refunded =
                                    service.getSubmission("E", "S", "A", bound.submissionNo());
                            assertThat(refunded.status()).isEqualTo("BOUND");
                            assertThat(refunded.source().state()).isEqualTo("BLOCKED");
                            assertThat(refunded.source().canStart()).isFalse();
                            sourceFacts.put("EXISTING", original);
                            var late =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            second.workOrderNo(),
                                            existing(
                                                    second,
                                                    second.taskNos().getFirst(),
                                                    "EXISTING"),
                                            "BIND2");
                            assertThat(late.status()).isEqualTo("SOURCE_PENDING");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isOne();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT allocated_qty FROM"
                                                            + " bty_source_allocation_guard",
                                                    BigDecimal.class))
                                    .isEqualByComparingTo("1");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT source_revision FROM"
                                                            + " bty_source_observation",
                                                    Long.class))
                                    .isEqualTo(2);
                        });
    }

    @Test
    void recoveryAcceptanceIsImmutableAndOriginalOperationDispatchesUnderCurrentB() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var submitted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            selection(root, root.taskNos().getFirst()),
                                            "KEY");
                            login(LoginUser.merchant("B", "current manager"));
                            var request =
                                    new RecoverSubmissionReq(
                                            submitted.revision(), "query original");
                            var accepted =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.requestSubmissionRecovery(
                                                            "E",
                                                            "S",
                                                            "B",
                                                            submitted.submissionNo(),
                                                            request,
                                                            "RECOVER"));
                            assertThat(accepted.recoveryNo()).startsWith("BTRQ");
                            settled(submitted.checkoutOperationNo(), 1);
                            var result =
                                    context.getBean(ServiceOperationRecoveryService.class)
                                            .recoverOperation(
                                                    "E", "S", "B", submitted.submissionNo());
                            assertThat(result.state()).isEqualTo("SUCCEEDED");
                            assertThat(
                                            service.requestSubmissionRecovery(
                                                    "E",
                                                    "S",
                                                    "B",
                                                    submitted.submissionNo(),
                                                    request,
                                                    "RECOVER"))
                                    .isEqualTo(accepted);
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.requestSubmissionRecovery(
                                                    "E",
                                                    "S",
                                                    "B",
                                                    submitted.submissionNo(),
                                                    new RecoverSubmissionReq(
                                                            submitted.revision(), "changed"),
                                                    "RECOVER"));
                            assertThat(
                                            context.getBean(ServiceOperationQueryService.class)
                                                    .getOperation(
                                                            "E", "S", "B", submitted.submissionNo())
                                                    .workOrderNo())
                                    .isEqualTo(root.workOrderNo());
                            assertThat(prepares).hasValue(1);
                        });
    }

    @Test
    void workOrderProjectsActualBindingAndNeverExecutionPermission() {
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
                            var detail =
                                    context.getBean(WorkOrderService.class)
                                            .getWorkOrder("E", "S", "A", root.workOrderNo());
                            assertThat(detail.tasks().getFirst().sourceState())
                                    .isEqualTo("BOUND_OBSERVED");
                            assertThat(detail.tasks().getFirst().canStart()).isFalse();
                        });
    }

    @Test
    void immutableDefinitionAndOriginalDraftParentsAreRequired() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var request = selection(root, root.taskNos().getFirst());
                            sql.update("UPDATE bty_service_definition SET store_no='OTHER'");
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    request,
                                                    "KEY"));
                            sql.update("UPDATE bty_service_definition SET store_no='S'");
                            sql.update("UPDATE bty_work_order_context SET work_order_no='MISSING'");
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    request,
                                                    "KEY"));
                            sql.update(
                                    "UPDATE bty_work_order_context SET work_order_no=?",
                                    root.workOrderNo());
                            sql.update(
                                    "UPDATE bty_service_submission SET"
                                        + " frozen_command=JSON_SET(frozen_command,'$.scopeHash',?)"
                                        + " WHERE action='OPEN'",
                                    "f".repeat(64));
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    request,
                                                    "KEY"));
                            assertThat(prepares).hasValue(0);
                        });
    }

    @Test
    void sameEndpointKeyCannotChangeFromSelectionToAddition() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            service.submitForWorkOrder(
                                    "E",
                                    "S",
                                    "A",
                                    root.workOrderNo(),
                                    selection(root, root.taskNos().getFirst()),
                                    "PERMANENT");
                            String definition =
                                    sql.queryForObject(
                                            "SELECT definition_version_no FROM bty_service_task"
                                                    + " LIMIT 1",
                                            String.class);
                            var changed =
                                    new SubmitServicesReq(
                                            0,
                                            1,
                                            List.of(),
                                            List.of(
                                                    new SubmitServicesReq.AddedItem(
                                                            "NEW", "SKU", definition, 1)),
                                            selection(root, root.taskNos().getFirst()).source());
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    root.workOrderNo(),
                                                    changed,
                                                    "PERMANENT"));
                            assertThat(prepares).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_service_task",
                                                    Integer.class))
                                    .isOne();
                        });
    }

    @Test
    void concurrentSameEndpointKeyReplaysOneCommittedSubmission() {
        raceEndpointKey(false, false);
    }

    @Test
    void concurrentChangedEndpointPayloadHasOneWinner() {
        raceEndpointKey(true, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossActionRaceRetainsEitherWinnersImmutableReceipt(boolean addWins) {
        raceEndpointKey(true, true, addWins ? 1 : 0);
    }

    private void raceEndpointKey(boolean changed, boolean added) {
        raceEndpointKey(changed, added, -1);
    }

    private record RaceResult(int index, ServiceSubmissionVO receipt, BizException failure) {}

    private void raceEndpointKey(boolean changed, boolean added, int firstRequest) {
        business().run(context -> raceEndpointKey(context, changed, added, firstRequest));
    }

    /** Both arrivals race freely unless a directed case explicitly holds the second request. */
    private void raceEndpointKey(
            ApplicationContext context, boolean changed, boolean added, int firstRequest) {
        var root = open(context, 2, "OPEN");
        var service = context.getBean(ServiceSubmissionService.class);
        var submit = selection(root, root.taskNos().getFirst());
        var other = selection(root, changed ? root.taskNos().getLast() : root.taskNos().getFirst());
        if (added) {
            String definition =
                    sql.queryForObject(
                            "SELECT definition_version_no FROM bty_service_task LIMIT 1",
                            String.class);
            other =
                    new SubmitServicesReq(
                            0,
                            1,
                            List.of(),
                            List.of(new SubmitServicesReq.AddedItem("ADD", "SKU", definition, 1)),
                            other.source());
        }
        var requests = List.of(submit, other);
        String namespace =
                IdempotencyScope.requestHash(
                        "BEAUTY_SUBMISSION_V1",
                        "MAIN",
                        "E",
                        "S",
                        "MERCHANT",
                        "MCH",
                        "A",
                        "BAPI-056",
                        IdempotencyScope.requestHash("RACING"));
        var arrived = new CountDownLatch(2);
        var accepted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var requestIndex = new ThreadLocal<Integer>();
        customerResolution =
                () -> {
                    if (calls.incrementAndGet() > 2) {
                        return;
                    }
                    arrived.countDown();
                    awaitRace(arrived);
                    if (firstRequest >= 0 && requestIndex.get() != firstRequest) {
                        awaitRace(accepted);
                    }
                };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures =
                    List.of(0, 1).stream()
                            .map(
                                    index ->
                                            executor.submit(
                                                    () -> {
                                                        login(LoginUser.merchant("A", "operator"));
                                                        requestIndex.set(index);
                                                        try {
                                                            var receipt =
                                                                    service.submitForWorkOrder(
                                                                            "E",
                                                                            "S",
                                                                            "A",
                                                                            root.workOrderNo(),
                                                                            requests.get(index),
                                                                            "RACING");
                                                            return new RaceResult(
                                                                    index, receipt, null);
                                                        } catch (BizException failure) {
                                                            return new RaceResult(
                                                                    index, null, failure);
                                                        } finally {
                                                            if (index == firstRequest) {
                                                                accepted.countDown();
                                                            }
                                                            requestIndex.remove();
                                                            SecurityContextHolder.clearContext();
                                                        }
                                                    }))
                            .toList();
            var results = new ArrayList<RaceResult>();
            for (var future : futures) {
                try {
                    results.add(future.get(25, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            var successes = results.stream().filter(result -> result.failure() == null).toList();
            var failures = results.stream().filter(result -> result.failure() != null).toList();
            assertThat(successes).hasSize(changed ? 1 : 2);
            assertThat(failures).hasSize(changed ? 1 : 0);
            failures.forEach(
                    result ->
                            assertThat(result.failure().errorCode()).isEqualTo(ErrorCode.CONFLICT));
            var winner = successes.getFirst();
            if (firstRequest >= 0) {
                assertThat(winner.index()).isEqualTo(firstRequest);
            }
            var original = winner.receipt();
            successes.forEach(
                    result -> {
                        assertThat(result.receipt().submissionNo())
                                .isEqualTo(original.submissionNo());
                        assertThat(result.receipt().action()).isEqualTo(original.action());
                    });
            var request = requests.get(winner.index());
            String action = request.additions().isEmpty() ? "SUBMIT_SOURCE" : "ADD_SOURCE";
            assertThat(original.action()).isEqualTo(action);
            assertThat(original.workOrderNo()).isEqualTo(root.workOrderNo());
            assertThat(original.originalScopeRevision()).isEqualTo(1);
            assertThat(original.targetScopeRevision())
                    .isEqualTo(action.equals("ADD_SOURCE") ? 2 : 1);
            String payload =
                    IdempotencyScope.requestHash(
                            root.workOrderNo(), json.writeValueAsString(request));
            var rows =
                    sql.queryForList(
                            "SELECT * FROM bty_service_submission WHERE entity_no='E' AND"
                                    + " store_no='S' AND work_order_no=? AND request_key_hash=? AND"
                                    + " action IN ('SUBMIT_SOURCE','ADD_SOURCE')",
                            root.workOrderNo(),
                            namespace);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row)
                    .containsEntry("submission_no", original.submissionNo())
                    .containsEntry("action", action)
                    .containsEntry("request_key_hash", namespace)
                    .containsEntry("payload_hash", payload);
            var receipts =
                    sql.queryForList(
                            "SELECT * FROM bty_action_log WHERE entity_no='E' AND store_no='S' AND"
                                    + " action='SOURCE_SUBMITTED' AND request_ref=?",
                            namespace);
            assertThat(receipts).hasSize(1);
            var receipt = receipts.getFirst();
            assertThat(receipt)
                    .containsEntry("aggregate_type", "BEAUTY_SOURCE_SUBMISSION")
                    .containsEntry("aggregate_no", original.submissionNo())
                    .containsEntry("operator_ref", "A")
                    .containsEntry("action_no", "BTSK" + namespace.substring(0, 28));
            assertThat(json.readTree((String) receipt.get("safe_snapshot")))
                    .isEqualTo(
                            json.valueToTree(
                                    Map.of(
                                            "schemaVersion",
                                            1,
                                            "namespace",
                                            namespace,
                                            "payload",
                                            payload,
                                            "workOrderNo",
                                            root.workOrderNo(),
                                            "submissionNo",
                                            original.submissionNo())));
            var command =
                    json.readValue(
                            (String) row.get("frozen_command"),
                            ServiceSubmissionServiceImpl.Command.class);
            assertThat(command.namespaceHash()).isEqualTo(namespace);
            assertThat(command.keyHash()).isEqualTo(IdempotencyScope.requestHash("RACING"));
            assertThat(command.tasks()).hasSize(1);
            var task = command.tasks().getFirst();
            assertThat(task.skuNo()).isEqualTo("SKU");
            assertThat(task.qty()).isEqualByComparingTo("1");
            assertThat(task.selectionRef())
                    .isEqualTo(
                            action.equals("ADD_SOURCE")
                                    ? "ADD"
                                    : request.tasks().getFirst().taskNo());
            assertThat(original.tasks())
                    .extracting(item -> item.taskNo())
                    .containsExactly(task.taskNo());
            if (action.equals("ADD_SOURCE")) {
                assertThat(root.taskNos()).doesNotContain(task.taskNo());
                assertThat(task.definitionVersionNo())
                        .isEqualTo(request.additions().getFirst().definitionVersionNo());
            } else {
                assertThat(task.taskNo()).isEqualTo(request.tasks().getFirst().taskNo());
            }
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM bty_service_task t JOIN bty_work_order w"
                                        + " ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no"
                                        + " AND w.work_order_no=t.work_order_no WHERE"
                                        + " t.entity_no='E' AND w.store_no='S' AND"
                                        + " t.work_order_no=?",
                                    Integer.class,
                                    root.workOrderNo()))
                    .isEqualTo(action.equals("ADD_SOURCE") ? 3 : 2);
            assertThat(prepares).hasValue(1);
            assertThat(prepared.operation().operationNo()).isEqualTo(original.submissionNo());
            assertThat(prepared.lines()).containsExactlyElementsOf(command.lines());
            assertThat(prepared.lines()).hasSize(1);
            assertThat(prepared.lines().getFirst().clientLineRef()).isEqualTo(task.clientLineRef());
            assertThat(prepared.lines().getFirst().skuNo()).isEqualTo("SKU");
            assertThat(prepared.lines().getFirst().quantity()).isOne();
            assertThat(created).isEmpty();
            assertThat(sourceFacts).isEmpty();
            assertThat(quotes).containsOnlyKeys(original.checkoutOperationNo());
            assertThat(original.quote()).isEqualTo(quotes.get(original.checkoutOperationNo()));
            var preparedBefore = prepared;
            var quotesBefore = Map.copyOf(quotes);
            var sourcesBefore = Map.copyOf(sourceFacts);
            var createdBefore = Set.copyOf(created);
            var tasksBefore = sql.queryForList("SELECT * FROM bty_service_task ORDER BY task_no");
            var submissionsBefore = immutableRaceSubmissions();
            var observationsBefore =
                    sql.queryForList("SELECT * FROM bty_source_observation ORDER BY id");
            var bindingsBefore = sql.queryForList("SELECT * FROM bty_trade_binding ORDER BY id");
            var allocationsBefore =
                    sql.queryForList("SELECT * FROM bty_source_allocation_guard ORDER BY id");
            assertThat(observationsBefore).isEmpty();
            assertThat(bindingsBefore).isEmpty();
            assertThat(allocationsBefore).isEmpty();
            var logsBefore = sql.queryForList("SELECT * FROM bty_action_log ORDER BY action_no");
            var replay =
                    service.submitForWorkOrder(
                            "E", "S", "A", root.workOrderNo(), request, "RACING");
            var current = service.getSubmission("E", "S", "A", original.submissionNo());
            assertThat(replay)
                    .as("winner replay returns the complete current projection")
                    .isEqualTo(current);
            assertThat(replay)
                    .as("only recovery lease revision may advance from the first projection")
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .ignoringFields("revision")
                    .isEqualTo(original);
            assertThat(replay.revision()).isGreaterThanOrEqualTo(original.revision());
            if (changed) {
                var loser = requests.get(failures.getFirst().index());
                var conflict =
                        Assertions.assertThrows(
                                BizException.class,
                                () ->
                                        service.submitForWorkOrder(
                                                "E",
                                                "S",
                                                "A",
                                                root.workOrderNo(),
                                                loser,
                                                "RACING"));
                assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);
            }
            assertThat(prepares).hasValue(1);
            assertThat(prepared).isEqualTo(preparedBefore);
            assertThat(quotes).isEqualTo(quotesBefore);
            assertThat(sourceFacts).isEqualTo(sourcesBefore);
            assertThat(created).isEqualTo(createdBefore);
            assertThat(sql.queryForList("SELECT * FROM bty_service_task ORDER BY task_no"))
                    .isEqualTo(tasksBefore);
            assertThat(immutableRaceSubmissions()).isEqualTo(submissionsBefore);
            assertThat(sql.queryForList("SELECT * FROM bty_source_observation ORDER BY id"))
                    .isEqualTo(observationsBefore);
            assertThat(sql.queryForList("SELECT * FROM bty_trade_binding ORDER BY id"))
                    .isEqualTo(bindingsBefore);
            assertThat(sql.queryForList("SELECT * FROM bty_source_allocation_guard ORDER BY id"))
                    .isEqualTo(allocationsBefore);
            assertThat(
                            sql.queryForList(
                                    "SELECT * FROM bty_action_log WHERE entity_no='E' AND"
                                            + " store_no='S' AND action='SOURCE_SUBMITTED' AND"
                                            + " request_ref=?",
                                    namespace))
                    .as("winner original immutable acceptance receipt survives replay unchanged")
                    .isEqualTo(receipts);
            assertThat(sql.queryForList("SELECT * FROM bty_action_log ORDER BY action_no"))
                    .isEqualTo(logsBefore);
        } finally {
            accepted.countDown();
            executor.shutdownNow();
            customerResolution = () -> {};
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    private static void awaitRace(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS))
                    .as("bounded submission race boundary")
                    .isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private List<Map<String, Object>> immutableRaceSubmissions() {
        return sql.queryForList(
                "SELECT entity_no, store_no, work_order_no, submission_no, action,"
                    + " request_key_hash, payload_hash, frozen_command FROM bty_service_submission"
                    + " ORDER BY submission_no");
    }

    @Test
    void sameEndpointKeyAcrossWorkOrdersCannotChangeTarget() {
        business()
                .run(
                        context -> {
                            var first = open(context, 1, "FIRST");
                            var second = open(context, 1, "SECOND");
                            var service = context.getBean(ServiceSubmissionService.class);
                            service.submitForWorkOrder(
                                    "E",
                                    "S",
                                    "A",
                                    first.workOrderNo(),
                                    selection(first, first.taskNos().getFirst()),
                                    "KEY");
                            Assertions.assertThrows(
                                    BizException.class,
                                    () ->
                                            service.submitForWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    second.workOrderNo(),
                                                    selection(second, second.taskNos().getFirst()),
                                                    "KEY"));
                            assertThat(prepares).hasValue(1);
                        });
    }

    @Test
    void unclaimedGuestHasExplicitCustomerRequiredBeforeAnyPurchase() {
        var owner =
                (GuestRegistrationPort)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {GuestRegistrationPort.class},
                                (proxy, method, args) -> {
                                    outside();
                                    if (method.getName().equals("get")) {
                                        return new GuestRegistrationPort.GuestFact(
                                                "GUEST", "REGISTERED", null, 1);
                                    }
                                    throw new AssertionError(method.getName());
                                });
        business()
                .withBean(GuestRegistrationPort.class, () -> owner)
                .run(
                        context -> {
                            login(LoginUser.merchant("A", "operator"));
                            var root =
                                    context.getBean(WorkOrderService.class)
                                            .openWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new OpenWorkOrderReq(
                                                            null,
                                                            "S",
                                                            List.of(
                                                                    new AppointmentItemReq(
                                                                            "SKU", 1)),
                                                            null,
                                                            "GUEST",
                                                            null,
                                                            List.of(),
                                                            null),
                                                    "OPEN");
                            var rejected =
                                    Assertions.assertThrows(
                                            BizException.class,
                                            () ->
                                                    context.getBean(ServiceSubmissionService.class)
                                                            .submitForWorkOrder(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    root.workOrderNo(),
                                                                    selection(
                                                                            root,
                                                                            root.taskNos()
                                                                                    .getFirst()),
                                                                    "KEY"));
                            assertThat(rejected.errorCode().name())
                                    .isEqualTo("BEAUTY_SOURCE_CUSTOMER_REQUIRED");
                            assertThat(prepares).hasValue(0);
                        });
    }

    @Test
    void knownOwnerDependencyFailureRetainsOriginalPendingOperation() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("OP", 1);
                            sourceRead =
                                    lookup -> {
                                        throw BizException.of(
                                                ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                                    };
                            var service = context.getBean(ServiceSubmissionService.class);
                            var pending =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.submitForWorkOrder(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            root.workOrderNo(),
                                                            existing(
                                                                    root,
                                                                    root.taskNos().getFirst(),
                                                                    "OP"),
                                                            "KEY"));
                            assertThat(pending.status()).isEqualTo("SOURCE_PENDING");
                            assertThat(pending.phase()).isEqualTo("SOURCE_UNKNOWN");
                            assertThat(pending.checkoutOperationNo()).isEqualTo("OP");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                        });
    }

    @Test
    void missingCheckoutOwnerIsExplicitAndCallerTransactionsCannotReachOwner() {
        checkoutAvailable = false;
        business()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(CheckoutPort.class)).isEmpty();
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var request = selection(root, root.taskNos().getFirst());
                            var pending =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "KEY");
                            assertThat(pending.phase()).isEqualTo("SOURCE_UNAVAILABLE");
                            assertThat(pending.quote()).isNull();
                            var tx =
                                    new TransactionTemplate(
                                            context.getBean(PlatformTransactionManager.class));
                            Assertions.assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            tx.execute(
                                                    status ->
                                                            service.recoverPending(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    pending.submissionNo())));
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                        });
    }

    @Test
    void concurrentDerivedActionsShareOnePermanentEndpointKey() {
        raceEndpointKey(true, true);
    }

    @Test
    void sameTaskDifferentKeysCannotCreateTwoAllocations() {
        business()
                .run(
                        context -> {
                            var first = open(context, 1, "OPEN1");
                            var second = first;
                            existingOwner("EXISTING", 1);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var start = new CountDownLatch(1);
                            try (var executor = Executors.newFixedThreadPool(2)) {
                                var futures =
                                        List.of(first, second).stream()
                                                .map(
                                                        root ->
                                                                executor.submit(
                                                                        () -> {
                                                                            login(
                                                                                    LoginUser
                                                                                            .merchant(
                                                                                                    "A",
                                                                                                    "operator"));
                                                                            start.await();
                                                                            String taskNo =
                                                                                    root.taskNos()
                                                                                            .getFirst();
                                                                            try {
                                                                                return service.submitForWorkOrder(
                                                                                                "E",
                                                                                                "S",
                                                                                                "A",
                                                                                                root
                                                                                                        .workOrderNo(),
                                                                                                existing(
                                                                                                        root,
                                                                                                        taskNo,
                                                                                                        "EXISTING"),
                                                                                                UUID.randomUUID()
                                                                                                        .toString())
                                                                                        .status();
                                                                            } catch (
                                                                                    BizException
                                                                                            conflict) {
                                                                                return "CONFLICT";
                                                                            } finally {
                                                                                SecurityContextHolder
                                                                                        .clearContext();
                                                                            }
                                                                        }))
                                                .toList();
                                start.countDown();
                                int winners = 0;
                                for (var future : futures) {
                                    try {
                                        if ("BOUND".equals(future.get(20, TimeUnit.SECONDS))) {
                                            winners++;
                                        }
                                    } catch (Exception failure) {
                                        throw new AssertionError(failure);
                                    }
                                }
                                assertThat(winners).isOne();
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT COUNT(*) FROM bty_trade_binding",
                                                        Integer.class))
                                        .isOne();
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT allocated_qty FROM"
                                                                + " bty_source_allocation_guard",
                                                        BigDecimal.class))
                                        .isEqualByComparingTo("1");
                            }
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "REQUOTE_REQUIRED"})
    void definitiveOwnerPhaseTerminatesLeaseAndAllowsNewSelection(String phase) {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var request = selection(root, root.taskNos().getFirst());
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "KEY");
                            String operation = accepted.checkoutOperationNo();
                            settled(operation, 1);
                            context.getBean(ServiceSourceObservation.class)
                                    .observe(
                                            new ServiceSourceObservation.Canonical(
                                                    "E",
                                                    "S",
                                                    "USR1",
                                                    operation,
                                                    "ORD-" + operation,
                                                    "SUB-" + operation),
                                            "A");
                            ownerPhases.put(operation, phase);
                            var rejected =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.recoverPending(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            accepted.submissionNo()));
                            assertThat(rejected.status()).isEqualTo("REJECTED");
                            assertThat(rejected.phase()).isEqualTo("OWNER_" + phase);
                            assertThat(rejected.source().state()).isEqualTo("UNKNOWN");
                            assertThat(rejected.source().lastVerifiedRevision()).isEqualTo(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT next_retry_at FROM"
                                                            + " bty_service_submission WHERE"
                                                            + " submission_no=?",
                                                    Object.class,
                                                    accepted.submissionNo()))
                                    .isNull();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                            var dispatched =
                                    context.getBean(ServiceOperationRecoveryService.class)
                                            .recoverOperation(
                                                    "E", "S", "A", accepted.submissionNo());
                            assertThat(dispatched.state()).isEqualTo("REJECTED");
                            assertThat(dispatched.recoveryAction()).isNull();
                            missingFindOperation = operation;
                            var replay =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "KEY");
                            assertThat(replay.status()).isEqualTo("REJECTED");
                            assertThat(replay.revision()).isEqualTo(rejected.revision());
                            assertThat(
                                            service.getSubmission(
                                                            "E", "S", "A", accepted.submissionNo())
                                                    .status())
                                    .isEqualTo("REJECTED");
                            var next =
                                    service.submitForWorkOrder(
                                            "E", "S", "A", root.workOrderNo(), request, "NEW-KEY");
                            assertThat(next.submissionNo()).isNotEqualTo(accepted.submissionNo());
                            assertThat(next.phase()).isEqualTo("AWAITING_CUSTOMER");
                            assertThat(prepares).hasValue(2);
                        });
    }

    @Test
    void existingSourceRecoveryReceiptDispatchesLocalSubmissionIdentity() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            existingOwner("EXTERNAL-OP", 1);
                            var service = context.getBean(ServiceSubmissionService.class);
                            var submitted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            existing(
                                                    root, root.taskNos().getFirst(), "EXTERNAL-OP"),
                                            "KEY");
                            assertThat(submitted.submissionNo())
                                    .isNotEqualTo(submitted.checkoutOperationNo());
                            var request =
                                    new RecoverSubmissionReq(
                                            submitted.revision(), "query original");
                            var receipt =
                                    service.requestSubmissionRecovery(
                                            "E",
                                            "S",
                                            "A",
                                            submitted.submissionNo(),
                                            request,
                                            "RECOVER");
                            assertThat(receipt.operationNo()).isEqualTo(submitted.submissionNo());
                            assertThat(
                                            service.requestSubmissionRecovery(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    submitted.submissionNo(),
                                                    request,
                                                    "RECOVER"))
                                    .isEqualTo(receipt);
                            assertThat(
                                            context.getBean(ServiceOperationQueryService.class)
                                                    .getOperation(
                                                            "E", "S", "A", receipt.operationNo())
                                                    .workOrderNo())
                                    .isEqualTo(root.workOrderNo());
                            assertThat(
                                            context.getBean(ServiceOperationRecoveryService.class)
                                                    .recoverOperation(
                                                            "E", "S", "A", receipt.operationNo())
                                                    .state())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isOne();
                        });
    }

    @Test
    void absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease() {
        business()
                .run(
                        context -> {
                            var root = open(context, 1, "OPEN");
                            var service = context.getBean(ServiceSubmissionService.class);
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            selection(root, root.taskNos().getFirst()),
                                            "KEY");
                            String operation = accepted.checkoutOperationNo();
                            settled(operation, 1);
                            var observer = context.getBean(ServiceSourceObservation.class);
                            var canonical =
                                    new ServiceSourceObservation.Canonical(
                                            "E",
                                            "S",
                                            "USR1",
                                            operation,
                                            "ORD-" + operation,
                                            "SUB-" + operation);
                            var prior = observer.observe(canonical, "A");
                            assertThat(prior.ready()).isTrue();
                            var mapper =
                                    context.getBean(BeautyMappers.ServiceSubmissionMapper.class);
                            var original =
                                    mapper.selectSubmission("E", "S", accepted.submissionNo());
                            var command =
                                    json.readValue(
                                            original.getFrozenCommand(),
                                            ServiceSubmissionServiceImpl.Command.class);
                            missingFindOperation = operation;
                            var pending =
                                    Assertions.assertDoesNotThrow(
                                            () ->
                                                    service.recoverPending(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            accepted.submissionNo()));
                            assertThat(pending.status()).isEqualTo("SOURCE_PENDING");
                            assertThat(pending.phase()).isEqualTo("SOURCE_UNKNOWN");
                            var current = observer.read(canonical);
                            assertThat(current.getState()).isEqualTo("UNKNOWN");
                            assertThat(current.getObservationEpoch()).isGreaterThan(prior.ticket());
                            assertThat(current.getVerifiedRevision()).isEqualTo(1);
                            assertThat(
                                            context.getBean(ServiceSourceBinding.class)
                                                    .bind(original, command, prior, "A")
                                                    .reason())
                                    .isEqualTo("SOURCE_OBSERVATION_CHANGED");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                            missingFindOperation = null;
                            assertThat(
                                            service.recoverPending(
                                                            "E", "S", "A", accepted.submissionNo())
                                                    .status())
                                    .isEqualTo("BOUND");
                            assertThat(prepares).hasValue(1);
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "REJECTED"})
    void postCheckoutClaimedGuestAbsencePropagatesWithoutAvailabilityCompletion(String ownerPhase) {
        var recoveryGuestReads = new AtomicInteger(-1);
        var guestClaimed = new AtomicBoolean();
        var guestOwner =
                (GuestRegistrationPort)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {GuestRegistrationPort.class},
                                (proxy, method, args) -> {
                                    outside();
                                    if (method.getName().equals("claim")) {
                                        guestClaimed.set(true);
                                        return new GuestRegistrationPort.Claim("GUEST", "USR1", 2);
                                    }
                                    if (method.getName().equals("get")
                                            || method.getName().equals("verify")) {
                                        // Initial current identity succeeds; after the checkout
                                        // read the live guest is absent.
                                        if (recoveryGuestReads.get() >= 0
                                                && recoveryGuestReads.getAndIncrement() >= 1) {
                                            throw BizException.of(ErrorCode.NOT_FOUND);
                                        }
                                        return new GuestRegistrationPort.GuestFact(
                                                "GUEST",
                                                guestClaimed.get() ? "CLAIMED" : "REGISTERED",
                                                guestClaimed.get() ? "USR1" : null,
                                                guestClaimed.get() ? 2 : 1);
                                    }
                                    throw new AssertionError(method.getName());
                                });
        business()
                .withBean(GuestRegistrationPort.class, () -> guestOwner)
                .run(
                        context -> {
                            login(LoginUser.merchant("A", "operator"));
                            var root =
                                    context.getBean(WorkOrderService.class)
                                            .openWorkOrder(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new OpenWorkOrderReq(
                                                            null,
                                                            "S",
                                                            List.of(
                                                                    new AppointmentItemReq(
                                                                            "SKU", 1)),
                                                            null,
                                                            "GUEST",
                                                            null,
                                                            List.of(),
                                                            null),
                                                    "OPEN");
                            context.getBean(GuestRegistrationService.class)
                                    .claimGuest(
                                            "E",
                                            "S",
                                            "A",
                                            "GUEST",
                                            new ClaimGuestReq(customerReference, "t".repeat(43)),
                                            "CLAIM");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_guest_claim_receipt",
                                                    Integer.class))
                                    .isOne();
                            var service = context.getBean(ServiceSubmissionService.class);
                            var accepted =
                                    service.submitForWorkOrder(
                                            "E",
                                            "S",
                                            "A",
                                            root.workOrderNo(),
                                            selection(root, root.taskNos().getFirst()),
                                            "KEY");
                            if (ownerPhase.equals("CREATED")) {
                                settled(accepted.checkoutOperationNo(), 1);
                            } else {
                                ownerPhases.put(accepted.checkoutOperationNo(), ownerPhase);
                            }
                            recoveryGuestReads.set(0);
                            var denied =
                                    Assertions.assertThrows(
                                            BizException.class,
                                            () ->
                                                    service.recoverPending(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            accepted.submissionNo()));
                            assertThat(denied.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT status FROM bty_service_submission"
                                                            + " WHERE submission_no=?",
                                                    String.class,
                                                    accepted.submissionNo()))
                                    .isEqualTo("RECOVERING");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT failure_code FROM"
                                                            + " bty_service_submission WHERE"
                                                            + " submission_no=?",
                                                    String.class,
                                                    accepted.submissionNo()))
                                    .isEqualTo("AWAITING_CUSTOMER");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT version FROM bty_service_submission"
                                                            + " WHERE submission_no=?",
                                                    Long.class,
                                                    accepted.submissionNo()))
                                    .isEqualTo(accepted.revision() + 1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM bty_trade_binding",
                                                    Integer.class))
                                    .isZero();
                            assertThat(prepares).hasValue(1);
                        });
    }
}
