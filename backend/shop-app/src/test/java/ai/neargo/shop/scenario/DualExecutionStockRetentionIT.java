package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.inventory.entity.InvExecutionRetention;
import ai.neargo.shop.inventory.entity.InvExecutionRetentionClaim;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.ReturnReceiptService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort.*;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Actual prepared HUMAN source, historic outbox producer and both physical owners; source only. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@TestPropertySource(properties = {
    "shop.inventory.stock-authority=DUAL",
    "shop.personnel.publication.archive-only=true", "shop.schedule.publication.archive-only=true",
    "shop.resource.publication.archive-only=true"
})
class DualExecutionStockRetentionIT extends DualExecutionStockRetentionFixture {
    @Test
    void fullDualParentProvesRealNativeRowsWithNullMarkerAndSurvivesTodaysManagedSwitch() {
        var request = prepared(true);
        var receipt = current(() -> aggregate.retainV2(request));
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(receipt.canStart()).isFalse();
        assertThat(receipt.components()).hasSize(2);
        var platform = receipt.components().getFirst().platform();
        var nativeProof = receipt.components().getLast().inventory().proof();
        assertThat(platform.component().original()).isEqualTo(request.lookup());
        assertThat(nativeProof.component().original()).isEqualTo(request.lookup());
        assertThat(nativeProof.heads()).allSatisfy(head -> assertThat(head.confirmationOperationNo()).isNull());
        assertThat(nativeProof.lines()).isNotEmpty();
        managed.saveGoodsMode(managed.goodsOf(entity, goods), "OFF", actor);
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var before = new JdbcTemplate(inventoryDataSource).queryForList(
                "SELECT id,qty,issued_qty,released_qty FROM inv_reservation_line WHERE reservation_id=? ORDER BY id",
                nativeProof.heads().getFirst().reservationId());
        var ref = request.reference();
        var seal = new ExecutionStockRetentionPort.ResourceSealLookup(ref, ref.plannedResourceCommandNo(),
                ref.plannedResourceCommandHash(), ref.planHash(), "cancel-" + sku,
                ExecutionEvidenceContract.hash("TEST_CANCEL", sku));
        assertThat(aggregate.releaseAfterNoEffectV2(new ExecutionStockRetentionPort.ReleaseAfterNoEffect(
                request.lookup(), seal)).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(new JdbcTemplate(inventoryDataSource).queryForList(
                "SELECT id,qty,issued_qty,released_qty FROM inv_reservation_line WHERE reservation_id=? ORDER BY id",
                nativeProof.heads().getFirst().reservationId())).isEqualTo(before);
    }

    @Test
    void actualEmptyReserveIsTypedNotApplicableAndDoesNotCreateNativeClaim() {
        var request = prepared(false);
        var receipt = current(() -> aggregate.retainV2(request));
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var observation = receipt.components().getLast();
        assertThat(observation.inventory()).isNull();
        assertThat(observation.notApplicable()).isNotNull();
        observation.notApplicable().validate();
        assertThat(observation.notApplicable().original().expectedNeeds()).isEmpty();
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=?", Integer.class,
                request.retentionNo())).isZero();
    }

    @Test
    void interruptedFirstPlatformPinContinuesOnlyTheAcceptedIntentWithoutHumanReplay() {
        var request = prepared(true);
        doAnswer(invocation -> { throw new IllegalStateException("owner unavailable before first pin"); })
                .when(platformComponent).retainComponent(any());
        var partial = current(() -> aggregate.retainV2(request));
        assertThat(partial.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(partial.components().getLast().inventory().proof()).isNotNull();
        var row = routes.find("MAIN", partial.routeNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Integer.class, request.retentionNo())).isZero();
        doCallRealMethod().when(platformComponent).retainComponent(any());
        doAnswer(invocation -> { throw new AssertionError("MACHINE cannot authorize or reinstall HUMAN"); })
                .when(evidence).authorizeCurrent(any());
        var result = aggregate.continueAcceptedRetentionV2(
                new AcceptedRouteLookup(
                        row.getRouteNo(), row.getRouteHash(), row.getAcceptedIntentNo(), row.getAcceptedIntentHash(),
                        request.lookup()));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(jdbc.queryForObject("SELECT created_by FROM prd_execution_retention WHERE retention_no=? LIMIT 1",
                String.class, request.retentionNo())).isEqualTo("SYSTEM:RETENTION");
    }

    @Test
    void unknownMirrorCannotBecomeEmptyAndAcceptedFrozenBytesSurviveLaterSourceAbsence() {
        var request = prepared(true);
        var receipt = current(() -> aggregate.retainV2(request));
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var row = routes.find("MAIN", receipt.routeNo());
        String bytes = row.getMirrorOriginalJson();
        jdbc.update("DELETE FROM sys_outbox WHERE aggregate_type='INVENTORY' AND aggregate_id=?",
                request.stockOriginal().orderNo());
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(routes.find("MAIN", receipt.routeNo()).getMirrorOriginalJson()).isEqualTo(bytes);
    }

    @Test
    void staleObservationEpochCannotReplaceANewerWholeRouteObservationAndQueriesDoNotAdvanceIt() {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        var old = routes.find("MAIN", retained.routeNo());
        String bytes = json.writeValueAsString(retained);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(routes.observe(old, "RETAINED", bytes, retained.receiptHash(), now.plusSeconds(30), now)).isOne();
        assertThat(routes.observe(old, "CONFLICT", bytes, retained.receiptHash(), now.plusSeconds(30), now)).isZero();
        long epoch = routes.find("MAIN", retained.routeNo()).getObservationEpoch();
        aggregate.queryOriginalV2(request.lookup());
        assertThat(routes.find("MAIN", retained.routeNo()).getObservationEpoch()).isEqualTo(epoch);
        assertThatThrownBy(() -> coordinator.reconcileAcceptedRoutes(null, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void equivalentHistoricalDuplicateKeepsOldestFrozenRosterButConflictingNewSubsetFails() {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        var saved = routes.find("MAIN", retained.routeNo()).getMirrorOriginalJson();
        var original = request.stockOriginal();
        bus.publish("same-" + sku, new ai.neargo.shop.invbridge.InventoryLifecycleCommand(
                ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE, original.orderNo(), original.needs().stream()
                        .map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(), n.sourceStoreNo(), n.fulfillmentRef(),
                                original.lifecycleVersion())).toList(), original.orderNo()));
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(routes.find("MAIN", retained.routeNo()).getMirrorOriginalJson()).isEqualTo(saved);
        bus.publish("different-" + sku, new ai.neargo.shop.invbridge.InventoryLifecycleCommand(
                ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE, original.orderNo(), List.of(), original.orderNo()));
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(routes.find("MAIN", retained.routeNo()).getMirrorOriginalJson()).isEqualTo(saved);
    }

    @Test
    void missingHistoricalReserveCannotBeInferredFromRealNativeRowsOrTodaysManagedFlags() {
        var request = prepared(true);
        jdbc.update("DELETE FROM sys_outbox WHERE aggregate_type='INVENTORY' AND aggregate_id=? AND event_type=?",
                request.stockOriginal().orderNo(), ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE);
        var result = current(() -> aggregate.retainV2(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(routes.find("MAIN", result.routeNo())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Integer.class, request.retentionNo())).isZero();
    }

    @Test
    void wrongCurrentSelfCannotAcceptARouteDespiteCompleteOriginalEvidence() {
        var request = prepared(true);
        assertThatThrownBy(() -> ManualServiceAdmissionIT.currentCashier(tokens, actor,
                () -> aggregate.retainV2(request))).isInstanceOf(RuntimeException.class);
        assertThat(routes.find("MAIN", ExecutionStockRetentionAggregateContract
                .routeNo(request.lookup()))).isNull();
    }

    @Test
    void dualReceiptAndProofUseExplicitV2DomainsAndIndependentIssuedExpectations() {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        var component = retained.components().getLast().inventory();
        assertThat(component.schema()).isEqualTo("INVENTORY_EXECUTION_DUAL_RETENTION_RECEIPT_V2");
        var proof = component.proof();
        assertThat(proof.proofHash()).isEqualTo(ExecutionEvidenceContract.digest(
                "INVENTORY_EXECUTION_DUAL_PROOF_V2", proof, "proofHash"));
        assertThat(proof.proofHash()).isNotEqualTo(ExecutionEvidenceContract.digest(
                "INVENTORY_EXECUTION_PROOF_V1", proof, "proofHash"));
        var expected = retained.components().stream().map(c ->
                new ComponentIssueExpectation(
                        c.componentKind(), c.componentNo(), c.componentHash(), retained.receiptHash())).toList();
        var result = aggregate.acknowledgeIssuedV2(
                new AggregateIssuedV2(
                        request.lookup(), retained.routeNo(), retained.routeHash(), expected));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=? AND issue_observation_hash"
                        + " IS NOT NULL", Integer.class, request.retentionNo())).isZero();
    }

    @Autowired ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier routeSchema;

    @ParameterizedTest
    @ValueSource(strings = {"route_json", "component_roster_json", "accepted_intent_json", "mirror_original_json"})
    void nativeRouteGuardsRejectReplacingAcceptedBytesAndPhysicalDeletion(String column) {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(routeSchema.installation()).isEqualTo(
                ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier.Installation.INSTALLED);
        assertThatThrownBy(() -> jdbc.update("UPDATE prd_execution_retention_route SET " + column
                + "='{}' WHERE route_no=?", retained.routeNo())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM prd_execution_retention_route WHERE route_no=?",
                retained.routeNo())).isInstanceOf(RuntimeException.class);
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }


    @Test
    void missingInventoryProviderPreservesRequiredRosterAndExistingPlatformPinUntilExactMachineCompletion() {
        var request = prepared(true);
        var target = AopTestUtils.getUltimateTargetObject(coordinator);
        var previous = ReflectionTestUtils.getField(target, "inventory");
        AggregateReceiptV2 partial;
        try {
            ReflectionTestUtils.setField(target, "inventory",
                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                            .getBeanProvider(InventoryExecutionStockRetentionPort.class));
            partial = current(() -> aggregate.retainV2(request));
            assertThat(partial.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
            assertThat(partial.components()).hasSize(2);
            assertThat(partial.components().getFirst().platform().proof()).isNotNull();
            assertThat(partial.components().getLast().inventory()).isNull();
        } finally {
            ReflectionTestUtils.setField(target, "inventory", previous);
        }
        var row = routes.find("MAIN", partial.routeNo());
        var result = aggregate.continueAcceptedRetentionV2(
                new AcceptedRouteLookup(
                        row.getRouteNo(), row.getRouteHash(), row.getAcceptedIntentNo(), row.getAcceptedIntentHash(),
                        request.lookup()));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(result.components()).hasSize(2);
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT created_by FROM inv_execution_retention_claim WHERE retention_no=?", String.class,
                request.retentionNo())).isEqualTo("SYSTEM:RETENTION");
    }

    @Test
    void consumedDualAckValidatesIndependentOwnerHashesAndRecordsEvidenceWithoutIssuingStock() {
        var request = prepared(true);
        String fulfillment = request.stockOriginal().needs().getFirst().fulfillmentRef();
        inventoryCommands.issue(fulfillment, request.stockOriginal().orderNo());
        for (var event : outboxRows.selectList(Wrappers.<ai.neargo.shop.event.SysOutbox>lambdaQuery()
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateType, "INVENTORY")
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, fulfillment)
                .eq(ai.neargo.shop.event.SysOutbox::getEventType, ai.neargo.shop.invbridge.InvMirrorEvent.ISSUE)
                .orderByAsc(ai.neargo.shop.event.SysOutbox::getId))) mirrorConsumer.consume(event);
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(retained.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var platform = retained.components().getFirst();
        var inventory = retained.components().getLast();
        String platformHash = ExecutionStockRetentionContract.issueObservationHash(request.lookup(),
                platform.platform().proof().rows());
        var nativeProof = inventory.inventory().proof();
        String nativeHash = InventoryExecutionStockRetentionContract.issueObservationHash(nativeProof.component(),
                nativeProof.heads(), nativeProof.lines(), nativeProof.outbounds());
        assertThat(platformHash).isNotEqualTo(nativeHash);
        var expected = List.of(
                new ComponentIssueExpectation(
                        platform.componentKind(), platform.componentNo(), platform.componentHash(), platformHash),
                new ComponentIssueExpectation(
                        inventory.componentKind(), inventory.componentNo(), inventory.componentHash(), nativeHash));
        var inv = new JdbcTemplate(inventoryDataSource);
        var before = inv.queryForList("SELECT id,doc_no,qty_delta,balance_after"
                + " FROM inv_ledger WHERE doc_no=? ORDER BY id",
                nativeProof.outbounds().getFirst().outboundNo());
        var wrong = expected.stream().map(e ->
                new ComponentIssueExpectation(
                        e.componentKind(), e.componentNo(), e.componentHash(), retained.receiptHash())).toList();
        assertThat(aggregate.acknowledgeIssuedV2(
                new AggregateIssuedV2(
                        request.lookup(), retained.routeNo(), retained.routeHash(), wrong)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(inv.queryForObject("SELECT issue_observation_hash FROM inv_execution_retention_claim"
                + " WHERE retention_no=?", String.class, request.retentionNo())).isNull();
        var ack = aggregate.acknowledgeIssuedV2(
                new AggregateIssuedV2(
                        request.lookup(), retained.routeNo(), retained.routeHash(), expected));
        assertThat(ack.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(ack.components().getFirst().platform().proof().issueObservationHash()).isEqualTo(platformHash);
        assertThat(ack.components().getLast().inventory().proof().issueObservationHash()).isEqualTo(nativeHash);
        assertThat(inv.queryForList("SELECT id,doc_no,qty_delta,balance_after"
                + " FROM inv_ledger WHERE doc_no=? ORDER BY id",
                nativeProof.outbounds().getFirst().outboundNo())).isEqualTo(before);
    }


    @Test
    void routeSchemaDriftFailsClosedBeforeNewOwnerEffectsAndCallerTransactionIsRejected() {
        var request = prepared(true);
        String temporary = "esrr_hidden_" + sku.replace('-', '_');
        jdbc.execute("RENAME TABLE prd_execution_retention_route TO " + temporary);
        try {
            assertThat(routeSchema.installation()).isEqualTo(
                    ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier.Installation.CORRUPT);
            assertThat(current(() -> aggregate.retainV2(request)).outcome())
                    .isEqualTo(ExecutionStockRetentionPort.Outcome.UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                    Integer.class, request.retentionNo())).isZero();
        } finally {
            jdbc.execute("RENAME TABLE " + temporary + " TO prd_execution_retention_route");
        }
        var manager = web.getBean("transactionManager",
                org.springframework.transaction.PlatformTransactionManager.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> aggregate.queryOriginalV2(request.lookup())))
                .isInstanceOf(IllegalStateException.class);
    }


    @Test
    void hostAcceptanceRechecksActualSelfAfterFreshOriginalAndMirrorIO() {
        var request = prepared(true);
        var once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object actual = invocation.callRealMethod();
            if (once.compareAndSet(true, false)) {
                jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no=?", employeeAccount);
            }
            return actual;
        }).when(evidence).queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        try {
            assertThatThrownBy(() -> current(() -> aggregate.retainV2(request))).isInstanceOf(BizException.class);
        } finally {
            doCallRealMethod().when(evidence).queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        }
        assertThat(routes.find("MAIN", ExecutionStockRetentionAggregateContract
                .routeNo(request.lookup()))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Integer.class, request.retentionNo())).isZero();
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=?", Integer.class,
                request.retentionNo())).isZero();
    }

    @Test
    void acceptedEmptySubsetRejectsLaterNonemptyCandidateWithoutReplacingRosterOrPlatformPin() {
        var request = prepared(false);
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(retained.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var before = routes.find("MAIN", retained.routeNo());
        String roster = before.getComponentRosterJson();
        String mirror = before.getMirrorOriginalJson();
        String authority = before.getAcceptedIntentHash();
        var original = request.stockOriginal();
        bus.publish("late-nonempty-" + sku, new ai.neargo.shop.invbridge.InventoryLifecycleCommand(
                ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE, original.orderNo(), original.needs().stream()
                        .map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(), n.sourceStoreNo(), n.fulfillmentRef(),
                                original.lifecycleVersion())).toList(), original.orderNo()));
        var queried = aggregate.queryOriginalV2(request.lookup());
        assertThat(queried.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(queried.components().getFirst().platform().proof()).isNotNull();
        var continued = aggregate.continueAcceptedRetentionV2(
                new AcceptedRouteLookup(
                        before.getRouteNo(), before.getRouteHash(), before.getAcceptedIntentNo(), authority,
                        request.lookup()));
        assertThat(continued.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        var after = routes.find("MAIN", retained.routeNo());
        assertThat(after.getState()).isEqualTo("CONFLICT");
        assertThat(after.getComponentRosterJson()).isEqualTo(roster);
        assertThat(after.getMirrorOriginalJson()).isEqualTo(mirror);
        assertThat(after.getAcceptedIntentHash()).isEqualTo(authority);
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=?", Integer.class,
                request.retentionNo())).isZero();
    }

    @Test
    void persistedBothZeroIntentCanCreateBothFirstPinsWithoutHumanReplayOrChangingAcceptance() {
        var route = acceptedWithoutPins();
        String bytes = routes.find("MAIN", route.routeNo()).getRouteJson();
        doAnswer(invocation -> { throw new AssertionError("saved HUMAN must not be replayed"); })
                .when(evidence).authorizeCurrent(any());
        var result = aggregate.continueAcceptedRetentionV2(acceptedLookup(route));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(routes.find("MAIN", route.routeNo()).getRouteJson()).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT created_by FROM prd_execution_retention WHERE retention_no=? LIMIT 1",
                String.class, route.original().retentionNo())).isEqualTo("SYSTEM:RETENTION");
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT created_by FROM inv_execution_retention_claim WHERE retention_no=?", String.class,
                route.original().retentionNo())).isEqualTo("SYSTEM:RETENTION");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "PLATFORM,PURPOSE", "PLATFORM,SCHEMA", "PLATFORM,TARGET",
        "INVENTORY,PURPOSE", "INVENTORY,SCHEMA", "INVENTORY,TARGET"
    })
    void eachRealOwnerRejectsPurposeSchemaAndTargetSubstitutionOfActualPersistedAuthority(String owner, String kind) {
        var route = acceptedWithoutPins();
        var component = route.components().stream().filter(c -> c.componentKind().name().equals(owner))
                .findFirst().orElseThrow();
        doAnswer(invocation -> {
            var actual = (AcceptedComponentAuthority)
                    invocation.callRealMethod();
            actual.validate(invocation.getArgument(0));
            var target = actual.target();
            if ("TARGET".equals(kind)) {
                var other = route.components().stream().filter(c -> c.componentKind() != component.componentKind())
                        .findFirst().orElseThrow();
                target = new AcceptedComponentLookup(
                        route.original(), route.acceptedIntentNo(), route.acceptedIntentHash(), other.componentKind(),
                        other.componentNo(), other.componentHash());
            }
            return new AcceptedComponentAuthority(
                    "SCHEMA".equals(kind) ? "UNSUPPORTED_AUTHORITY_V9" : actual.schema(),
                    "PURPOSE".equals(kind) ? "ISSUE_EXECUTION_STOCK" : actual.purpose(), target, actual.outcome(),
                    actual.reason(), actual.route(), actual.revision(), actual.observationEpoch(), null).signed();
        }).when(acceptedIntents).queryAcceptedComponent(any());
        try {
            if ("PLATFORM".equals(owner)) {
                var lookup = new PlatformExecutionStockRetentionComponent.ComponentLookup(
                        component.platform());
                assertThatThrownBy(() -> platformComponent.continueAcceptedRetention(
                        new PlatformExecutionStockRetentionComponent.AcceptedRetention(
                                lookup, route.acceptedIntentNo(), route.acceptedIntentHash())))
                        .isInstanceOf(IllegalArgumentException.class);
            } else {
                var lookup = new InventoryExecutionStockRetentionPort.ComponentLookup(component.inventory());
                assertThatThrownBy(() -> inventoryComponent.continueAcceptedRetention(
                        new InventoryExecutionStockRetentionPort.AcceptedRetention(lookup, route.acceptedIntentNo(),
                                route.acceptedIntentHash()))).isInstanceOf(IllegalArgumentException.class);
            }
        } finally {
            doCallRealMethod().when(acceptedIntents).queryAcceptedComponent(any());
        }
        assertNoPins(route.original());
        assertThat(routes.find("MAIN", route.routeNo()).getAcceptedIntentHash()).isEqualTo(route.acceptedIntentHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROOF_DOMAIN", "RECEIPT_SCHEMA"})
    void realHostConsumerRejectsNativeV1SubstitutionIntoOtherwiseActualDualOwnerEvidence(String mutation) {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        var actualProof = retained.components().getLast().inventory().proof();
        doAnswer(invocation -> {
            var actual = (InventoryExecutionStockRetentionPort.ComponentReceipt) invocation.callRealMethod();
            actual.proof().validate();
            var proof = actual.proof();
            String schema = actual.schema();
            if ("PROOF_DOMAIN".equals(mutation)) {
                proof = new InventoryExecutionStockRetentionPort.InventoryProof(proof.component(),
                        proof.acceptedEvidenceHash(), proof.acceptedAuthorityHash(), proof.ownerSetHash(),
                                proof.heads(),
                        proof.lines(), proof.outbounds(), proof.refundClaims(), proof.returnClaims(),
                                proof.disposition(),
                        proof.state(), proof.revision(), proof.issueObservationHash(),
                        ExecutionEvidenceContract.digest("INVENTORY_EXECUTION_PROOF_V1", proof, "proofHash"));
            } else schema = "INVENTORY_EXECUTION_RETENTION_RECEIPT_V1";
            var changed = new InventoryExecutionStockRetentionPort.ComponentReceipt(schema, actual.componentNo(),
                    actual.componentHash(), actual.referenceHash(), actual.requestHash(), actual.outcome(),
                            actual.reason(),
                    proof, actual.observedAt(), null);
            return new InventoryExecutionStockRetentionPort.ComponentReceipt(schema, changed.componentNo(),
                    changed.componentHash(), changed.referenceHash(), changed.requestHash(), changed.outcome(),
                    changed.reason(), changed.proof(), changed.observedAt(),
                    ExecutionEvidenceContract.digest(schema, changed, "receiptHash"));
        }).when(inventoryComponent).queryOriginalComponent(any());
        try {
            var result = aggregate.queryOriginalV2(request.lookup());
            assertThat(result.outcome()).isNotEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            assertThat(result.components().getFirst().platform().proof()).isNotNull();
        } finally {
            doCallRealMethod().when(inventoryComponent).queryOriginalComponent(any());
        }
        assertThat(aggregate.queryOriginalV2(request.lookup()).components().getLast().inventory().proof())
                .isEqualTo(actualProof);
    }

    @Test
    void ownerCommitWithLostResponseIsResolvedByActualOriginalQueryWithoutDuplicatePins() {
        var request = prepared(true);
        doAnswer(invocation -> {
            var actual = (PlatformExecutionStockRetentionComponent.ComponentReceipt)
                    invocation.callRealMethod();
            assertThat(actual.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            throw new IllegalStateException("response lost after actual owner commit");
        }).when(platformComponent).retainComponent(any());
        var result = current(() -> aggregate.retainV2(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(result.components().getFirst().platform().proof()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Integer.class, request.retentionNo())).isEqualTo(result.components().getFirst().platform().proof()
                        .ownerSetCount());
    }

    @Test
    void interruptionAfterSecondOwnerBeforeHostCasPreservesBothPinsAndResumesTheSameIntent() {
        var request = prepared(true);
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                    Integer.class, request.retentionNo())).isPositive();
            assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                    "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=?", Integer.class,
                    request.retentionNo())).isOne();
            throw new IllegalStateException("interrupted after both owner commits before HOST observation CAS");
        }).when(routes).observe(any(), any(), any(), any(), any(), any());
        try {
            assertThatThrownBy(() -> current(() -> aggregate.retainV2(request)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            doCallRealMethod().when(routes).observe(any(), any(), any(), any(), any(), any());
        }
        var row = routes.find("MAIN", ExecutionStockRetentionAggregateContract
                .routeNo(request.lookup()));
        assertThat(row.getObservationsJson()).isNull();
        assertThat(row.getObservationEpoch()).isZero();
        var route = json.readValue(row.getRouteJson(),
                RouteOriginalV2.class);
        assertThat(aggregate.continueAcceptedRetentionV2(acceptedLookup(route)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void partialIssuedAcknowledgementRetriesTheExactIndependentOwnerObservationsWithoutStockEffects() {
        var retained = consumedRetention();
        var request = issuedRequest(retained);
        var nativeProof = retained.components().getLast().inventory().proof();
        var inv = new JdbcTemplate(inventoryDataSource);
        var before = inv.queryForList("SELECT id,doc_no,qty_delta,balance_after"
                + " FROM inv_ledger WHERE doc_no=? ORDER BY id",
                nativeProof.outbounds().getFirst().outboundNo());
        doAnswer(invocation -> { throw new IllegalStateException("native acknowledgement unavailable"); })
                .when(inventoryComponent).acknowledgeIssued(any());
        var partial = aggregate.acknowledgeIssuedV2(request);
        assertThat(partial.components().getFirst().platform().proof().issueObservationHash())
                .isEqualTo(request.expected().getFirst().expectedIssueObservationHash());
        assertThat(partial.components().getLast().inventory().proof().issueObservationHash()).isNull();
        doCallRealMethod().when(inventoryComponent).acknowledgeIssued(any());
        var complete = aggregate.acknowledgeIssuedV2(request);
        var replay = aggregate.acknowledgeIssuedV2(request);
        assertThat(complete.components().getLast().inventory().proof().issueObservationHash())
                .isEqualTo(request.expected().getLast().expectedIssueObservationHash());
        assertThat(replay.components().getFirst().platform().proof().issueObservationHash())
                .isEqualTo(request.expected().getFirst().expectedIssueObservationHash());
        assertThat(replay.components().getLast().inventory().proof().issueObservationHash())
                .isEqualTo(request.expected().getLast().expectedIssueObservationHash());
        assertThat(inv.queryForList("SELECT id,doc_no,qty_delta,balance_after"
                + " FROM inv_ledger WHERE doc_no=? ORDER BY id",
                nativeProof.outbounds().getFirst().outboundNo())).isEqualTo(before);
    }

    @Test
    void acceptedEmptySubsetProviderAbsenceRemainsPendingAndKeepsFrozenNotApplicableIdentity() {
        var request = prepared(false);
        var retained = current(() -> aggregate.retainV2(request));
        var before = routes.find("MAIN", retained.routeNo());
        doAnswer(invocation -> { throw new IllegalStateException("MIRROR_PROVIDER_UNAVAILABLE"); })
                .when(mirrorEvidence).validateFrozen(any());
        try {
            var result = aggregate.queryOriginalV2(request.lookup());
            assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
            assertThat(result.components().getFirst().platform().proof()).isNotNull();
            var row = routes.find("MAIN", retained.routeNo());
            assertThat(row.getComponentRosterJson()).isEqualTo(before.getComponentRosterJson());
            assertThat(row.getState()).isEqualTo(before.getState());
            assertThat(row.getObservationEpoch()).isEqualTo(before.getObservationEpoch());
        } finally {
            doCallRealMethod().when(mirrorEvidence).validateFrozen(any());
        }
    }

}
