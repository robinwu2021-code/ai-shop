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
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
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
    @MockitoSpyBean ServiceExecutionPreparationPort preparationBridge;

    @Test
    void nativeRetainedObservationPassesActualBeautyCoordinatorAndRejectsOtherOriginalProofs() {
        var originals = nativePreparedPair();
        var request = originals.getFirst();
        var otherRequest = originals.getLast();
        var otherReceipt = current(() -> aggregate.retainV2(otherRequest));
        assertThat(otherReceipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var otherObservation = coordinator.queryPreparationObservation(otherRequest.lookup());
        otherObservation.validate(otherRequest.lookup());
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(retained.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var observation = coordinator.queryPreparationObservation(request.lookup());
        observation.validate(request.lookup());
        var bindingJson =
                jdbc.queryForObject(
                        "SELECT binding_json FROM bty_service_execution_fence WHERE execution_no=?",
                        String.class,
                        request.reference().executionNo());
        var binding =
                json.treeToValue(
                        json.readTree(bindingJson).get("binding"),
                        ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding.class);
        var grant = executionOwner.queryOriginal(new ExecutionLookup(binding));
        var beauty = web.getBean(type("service.impl.ServiceTaskStockPreparationCoordinator"));
        var progress = current(() -> call(beauty, "prepareAcceptedOriginal", binding, grant));
        assertThat(value(progress, "preparationState")).isEqualTo("STOCK_RETAINED");
        assertThat(value(progress, "canStart")).isEqualTo(false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lookup_json FROM bty_service_execution_stock_intent WHERE"
                                        + " execution_no=?",
                                String.class,
                                binding.executionNo()))
                .isEqualTo(json.writeValueAsString(request.lookup()));
        var storedReceipt =
                jdbc.queryForObject(
                        "SELECT receipt_json FROM bty_service_execution_stock_intent WHERE"
                                + " execution_no=?",
                        String.class,
                        binding.executionNo());
        var stored =
                json.readValue(
                        storedReceipt,
                        ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation
                                .class);
        stored.validate(request.lookup());
        assertThat(stored.receipt().outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(nativePins(request.lookup())).isNotEmpty();
        assertInvalidNativeObservations(
                request.lookup(), observation, otherObservation, binding, beauty, storedReceipt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT receipt_json FROM bty_service_execution_stock_intent WHERE"
                                        + " execution_no=?",
                                String.class,
                                binding.executionNo()))
                .isEqualTo(storedReceipt);
    }

    @Test
    void persistedSemanticBadRouteUsesRealDueSelectionAndRetryCasWhileLaterNativePinsRemain() {
        var request = prepared(true);
        var accepted = current(() -> aggregate.retainV2(request));
        assertThat(accepted.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var original = routes.find("MAIN", accepted.routeNo());
        var pins = nativePins(request.lookup());
        assertThat(pins).isNotEmpty();
        assertThat(
                        new org.springframework.jdbc.core.JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        Integer.class,
                                        request.retentionNo()))
                .isPositive();
        var bad = new ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute();
        org.springframework.beans.BeanUtils.copyProperties(original, bad);
        bad.setId(null);
        String key =
                ExecutionEvidenceContract.hash(
                        "TEST_PERSISTED_SEMANTIC_BAD_ROUTE", accepted.routeNo());
        bad.setRouteNo(key);
        bad.setRetentionNo(key);
        bad.setAcceptedIntentNo(key);
        bad.setExecutionNo("bad-semantic-route");
        bad.setState("RETAIN_PENDING");
        bad.setNextRetryAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        // JSON_VALID and every original CHECK/trigger remain active: {} is valid JSON but not a
        // valid RouteOriginalV2.
        bad.setRouteJson("{}");
        assertThat(routes.insert(bad)).isOne();
        // Existing accepted original is moved to pending through the real observation CAS, without
        // changing receipt/pins.
        var now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(
                        routes.observe(
                                original,
                                "RETAIN_PENDING",
                                original.getObservationsJson(),
                                original.getObservationsHash(),
                                now.minusMinutes(1),
                                now))
                .isOne();
        var goodBefore = routes.find("MAIN", accepted.routeNo());
        var selected = routes.dueGroup(now, LocalDateTime.of(1970, 1, 1, 0, 0), 0, false, 100);
        assertThat(
                        selected.stream()
                                .map(
                                        ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute
                                                ::getId)
                                .toList())
                .containsSubsequence(bad.getId(), original.getId());
        var result = coordinator.reconcileAcceptedRoutes(null, 100);
        assertThat(result.failures())
                .anySatisfy(
                        failure -> {
                            assertThat(failure.routeRowId()).isEqualTo(bad.getId());
                            assertThat(failure.retryDeferred()).isTrue();
                        });
        assertThat(result.receipts())
                .anySatisfy(
                        receipt -> {
                            assertThat(receipt.retentionNo()).isEqualTo(request.retentionNo());
                            assertThat(receipt.outcome())
                                    .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
                        });
        var badAfter = routes.find("MAIN", key);
        assertThat(badAfter.getRouteJson()).isEqualTo("{}");
        assertThat(badAfter.getObservationsJson()).isEqualTo(bad.getObservationsJson());
        assertThat(badAfter.getRequestHash()).isEqualTo(bad.getRequestHash());
        assertThat(badAfter.getRouteHash()).isEqualTo(bad.getRouteHash());
        assertThat(badAfter.getNextRetryAt()).isAfter(now);
        assertThat(badAfter.getRevision()).isEqualTo(bad.getRevision() + 1);
        var goodAfter = routes.find("MAIN", accepted.routeNo());
        assertThat(goodAfter.getRouteJson()).isEqualTo(goodBefore.getRouteJson());
        assertThat(goodAfter.getRouteHash()).isEqualTo(goodBefore.getRouteHash());
        assertThat(goodAfter.getState()).isEqualTo("RETAINED");
        var refreshed = json.readValue(goodAfter.getObservationsJson(), AggregateReceiptV2.class);
        var frozenRoute = json.readValue(goodAfter.getRouteJson(), RouteOriginalV2.class);
        frozenRoute.validate();
        new ExecutionStockRetentionPreparationObservation(
                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                        refreshed,
                        frozenRoute.components())
                .validate(request.lookup());
        assertThat(refreshed.receiptHash()).isEqualTo(goodAfter.getObservationsHash());
        assertThat(refreshed.referenceHash()).isEqualTo(request.reference().referenceHash());
        assertThat(refreshed.retentionNo()).isEqualTo(request.retentionNo());
        assertThat(refreshed.requestHash()).isEqualTo(request.requestHash());
        assertThat(refreshed.routeNo()).isEqualTo(goodAfter.getRouteNo());
        assertThat(refreshed.routeHash()).isEqualTo(goodAfter.getRouteHash());
        assertThat(refreshed.ownerRosterHash()).isEqualTo(goodAfter.getComponentRosterHash());
        assertThat(refreshed.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(goodAfter.getRevision()).isGreaterThan(goodBefore.getRevision());
        assertThat(nativePins(request.lookup())).isEqualTo(pins);
    }

    @Test
    void nativeFinalEvidenceMembershipRevocationKeepsAcceptedPinsAndRejectsHumanReadiness() {
        var request = prepared(true);
        var bindingJson =
                jdbc.queryForObject(
                        "SELECT binding_json FROM bty_service_execution_fence WHERE execution_no=?",
                        String.class,
                        request.reference().executionNo());
        var binding =
                json.treeToValue(json.readTree(bindingJson).get("binding"), ExecutionBinding.class);
        var grant = executionOwner.queryOriginal(new ExecutionLookup(binding));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var pinsAtRevocation =
                new java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>>();
        doAnswer(
                        invocation -> {
                            var result = invocation.callRealMethod();
                            if (calls.incrementAndGet() == 3) {
                                pinsAtRevocation.set(nativePins(request.lookup()));
                                assertThat(pinsAtRevocation.get()).isNotEmpty();
                                assertThat(
                                                jdbc.update(
                                                        "DELETE FROM mch_store_role WHERE"
                                                            + " mch_account_no=? AND store_no=?",
                                                        employeeAccount,
                                                        store))
                                        .isPositive();
                            }
                            return result;
                        })
                .when(preparationBridge)
                .queryOriginalEvidence(any());
        var beauty = web.getBean(type("service.impl.ServiceTaskStockPreparationCoordinator"));
        assertThatThrownBy(
                        () ->
                                current(
                                        () ->
                                                call(
                                                        beauty,
                                                        "prepareAcceptedOriginal",
                                                        binding,
                                                        grant)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ai.neargo.shop.common.ErrorCode.FORBIDDEN));
        assertThat(calls).hasValue(3);
        org.mockito.Mockito.verify(preparationBridge).retainStock(request);
        assertThat(nativePins(request.lookup())).isEqualTo(pinsAtRevocation.get());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT receipt_json FROM bty_service_execution_stock_intent WHERE"
                                        + " execution_no=?",
                                String.class,
                                binding.executionNo()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lookup_json FROM bty_service_execution_stock_intent WHERE"
                                        + " execution_no=?",
                                String.class,
                                binding.executionNo()))
                .isEqualTo(json.writeValueAsString(request.lookup()));
        assertThat(nativePins(request.lookup())).isNotEmpty();
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        Integer.class,
                                        request.retentionNo()))
                .isPositive();
    }

    private List<ExecutionStockRetentionPort.RetainExecutionStock> nativePreparedPair() {
        managed.saveGoodsMode(managed.goodsOf(entity, goods), "ON", actor);
        var flow = ready(true, 2);
        var taskRows =
                jdbc.queryForList(
                        "SELECT task_no,version,binding_revision FROM bty_service_task"
                                + " WHERE work_order_no=? ORDER BY task_no",
                        flow.request().workOrderNo());
        assertThat(taskRows).hasSize(2);
        var originals = new ArrayList<ExecutionStockRetentionPort.RetainExecutionStock>();
        for (int index = 0; index < taskRows.size(); index++) {
            var task = taskRows.get(index);
            var start =
                    new FreezeStart(
                            flow.request().scope(),
                            flow.request().workOrderNo(),
                            (String) task.get("task_no"),
                            ((Number) task.get("version")).longValue(),
                            ((Number) task.get("binding_revision")).longValue(),
                            flow.request().expectedScopeRevision(),
                            "native-pair-" + index + "-" + sku);
            var grant = prepare(start);
            var ref =
                    current(
                            () ->
                                    executionOwner.referenceOriginal(
                                            new ExecutionLookup(grant.binding())));
            var source = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref));
            originals.add(
                    ExecutionStockRetentionContract.request(
                            ref,
                            source.facts().stockOriginal(),
                            source.facts().stockConfirmationOperationNo()));
        }
        for (var event :
                outboxRows.selectList(
                        Wrappers.<ai.neargo.shop.event.SysOutbox>lambdaQuery()
                                .eq(ai.neargo.shop.event.SysOutbox::getAggregateType, "INVENTORY")
                                .eq(
                                        ai.neargo.shop.event.SysOutbox::getAggregateId,
                                        originals.getFirst().stockOriginal().orderNo())
                                .orderByAsc(ai.neargo.shop.event.SysOutbox::getId)))
            mirrorConsumer.consume(event);
        return originals;
    }

    private void assertInvalidNativeObservations(
            ExecutionStockRetentionPort.RetentionLookup original,
            ExecutionStockRetentionPreparationObservation valid,
            ExecutionStockRetentionPreparationObservation other,
            ExecutionBinding binding,
            Object beauty,
            String persistedReceipt) {
        var first = valid.receipt().components().getFirst();
        var last = valid.receipt().components().getLast();
        var inner = last.inventory();
        assertThat(inner.proof()).isNotNull();
        var wrongSchema =
                new InventoryExecutionStockRetentionPort.ComponentReceipt(
                        "WRONG_INVENTORY_SCHEMA",
                        inner.componentNo(),
                        inner.componentHash(),
                        inner.referenceHash(),
                        inner.requestHash(),
                        inner.outcome(),
                        inner.reason(),
                        inner.proof(),
                        inner.observedAt(),
                        null);
        wrongSchema = signedInventory(wrongSchema);
        var wrongOutcome =
                new InventoryExecutionStockRetentionPort.ComponentReceipt(
                        inner.schema(),
                        inner.componentNo(),
                        inner.componentHash(),
                        inner.referenceHash(),
                        inner.requestHash(),
                        ExecutionStockRetentionPort.Outcome.PENDING,
                        "EXPLICIT_PENDING",
                        inner.proof(),
                        inner.observedAt(),
                        null);
        wrongOutcome = signedInventory(wrongOutcome);
        var candidates =
                List.of(
                        replaced(
                                valid,
                                List.of(
                                        new ComponentObservationV2(
                                                first.componentKind(),
                                                first.componentNo(),
                                                first.componentHash(),
                                                first.status(),
                                                other.receipt().components().getFirst().platform(),
                                                null,
                                                null),
                                        last)),
                        replaced(
                                valid,
                                List.of(
                                        first,
                                        new ComponentObservationV2(
                                                last.componentKind(),
                                                last.componentNo(),
                                                last.componentHash(),
                                                last.status(),
                                                null,
                                                other.receipt().components().getLast().inventory(),
                                                null))),
                        replaced(valid, List.of(first)),
                        replaced(valid, List.of(first, first)),
                        replaced(
                                valid,
                                List.of(
                                        new ComponentObservationV2(
                                                first.componentKind(),
                                                "b".repeat(64),
                                                first.componentHash(),
                                                first.status(),
                                                first.platform(),
                                                null,
                                                null),
                                        last)),
                        replaced(
                                valid,
                                List.of(
                                        first,
                                        new ComponentObservationV2(
                                                last.componentKind(),
                                                last.componentNo(),
                                                last.componentHash(),
                                                last.status(),
                                                null,
                                                wrongSchema,
                                                null))),
                        replaced(
                                valid,
                                List.of(
                                        first,
                                        new ComponentObservationV2(
                                                last.componentKind(),
                                                last.componentNo(),
                                                last.componentHash(),
                                                last.status(),
                                                null,
                                                wrongOutcome,
                                                null))),
                        new ExecutionStockRetentionPreparationObservation(
                                valid.schema(), valid.receipt(), other.roster()));
        var pins = nativePins(original);
        long version =
                jdbc.queryForObject(
                        "SELECT version FROM bty_service_execution_stock_intent WHERE"
                                + " execution_no=?",
                        Long.class,
                        binding.executionNo());
        for (var candidate : candidates) {
            assertThatThrownBy(() -> candidate.validate(original))
                    .isInstanceOf(IllegalArgumentException.class);
            org.mockito.Mockito.doReturn(candidate)
                    .when(preparationBridge)
                    .queryStockOriginal(original);
            var progress = call(beauty, "queryAcceptedOriginal", binding);
            assertThat(value(progress, "preparationState")).isEqualTo("STOCK_RETENTION_PENDING");
            assertThat(value(progress, "canStart")).isEqualTo(false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM bty_service_execution_stock_intent WHERE"
                                            + " execution_no=?",
                                    Long.class,
                                    binding.executionNo()))
                    .isEqualTo(version);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT receipt_json FROM bty_service_execution_stock_intent"
                                            + " WHERE execution_no=?",
                                    String.class,
                                    binding.executionNo()))
                    .isEqualTo(persistedReceipt);
            assertThat(nativePins(original)).isEqualTo(pins);
        }
        var tampered = candidates.getFirst();
        var prior =
                json.readValue(
                        persistedReceipt, ExecutionStockRetentionPreparationObservation.class);
        assertThat(
                        jdbc.update(
                                "UPDATE bty_service_execution_stock_intent SET"
                                        + " receipt_json=?,receipt_hash=?,"
                                        + "observation_epoch=observation_epoch+1,version=version+1"
                                        + " WHERE execution_no=? AND version=?",
                                json.writeValueAsString(tampered),
                                tampered.receipt().receiptHash(),
                                binding.executionNo(),
                                version))
                .isOne();
        try {
            assertThatThrownBy(
                            () ->
                                    current(
                                            () ->
                                                    call(
                                                            beauty,
                                                            "projectOperation",
                                                            entity,
                                                            store,
                                                            employeeAccount,
                                                            binding.submissionNo())))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            assertThat(
                            jdbc.update(
                                    "UPDATE bty_service_execution_stock_intent SET"
                                        + " receipt_json=?,receipt_hash=?,"
                                        + "observation_epoch=observation_epoch+1,version=version+1"
                                        + " WHERE execution_no=? AND version=?",
                                    persistedReceipt,
                                    prior.receipt().receiptHash(),
                                    binding.executionNo(),
                                    version + 1))
                    .isOne();
        }
        assertThat(nativePins(original)).isEqualTo(pins);
    }

    private static InventoryExecutionStockRetentionPort.ComponentReceipt signedInventory(
            InventoryExecutionStockRetentionPort.ComponentReceipt raw) {
        return new InventoryExecutionStockRetentionPort.ComponentReceipt(
                raw.schema(),
                raw.componentNo(),
                raw.componentHash(),
                raw.referenceHash(),
                raw.requestHash(),
                raw.outcome(),
                raw.reason(),
                raw.proof(),
                raw.observedAt(),
                ExecutionEvidenceContract.digest(raw.schema(), raw, "receiptHash"));
    }

    private static ExecutionStockRetentionPreparationObservation replaced(
            ExecutionStockRetentionPreparationObservation source,
            List<ComponentObservationV2> components) {
        var raw = source.receipt();
        var changed =
                new AggregateReceiptV2(
                        raw.schema(),
                        raw.referenceHash(),
                        raw.retentionNo(),
                        raw.requestHash(),
                        raw.routeNo(),
                        raw.routeHash(),
                        raw.frozenAuthority(),
                        raw.ownerRosterHash(),
                        components,
                        raw.outcome(),
                        raw.reason(),
                        raw.observedAt(),
                        null);
        var signed =
                new AggregateReceiptV2(
                        changed.schema(),
                        changed.referenceHash(),
                        changed.retentionNo(),
                        changed.requestHash(),
                        changed.routeNo(),
                        changed.routeHash(),
                        changed.frozenAuthority(),
                        changed.ownerRosterHash(),
                        changed.components(),
                        changed.outcome(),
                        changed.reason(),
                        changed.observedAt(),
                        ExecutionEvidenceContract.digest(changed.schema(), changed, "receiptHash"));
        return new ExecutionStockRetentionPreparationObservation(
                source.schema(), signed, source.roster());
    }

    private List<Map<String, Object>> nativePins(
            ExecutionStockRetentionPort.RetentionLookup lookup) {
        var pins = new java.util.ArrayList<Map<String, Object>>();
        pins.addAll(
                jdbc.queryForList(
                        "SELECT * FROM prd_execution_retention WHERE retention_no=? ORDER BY id",
                        lookup.retentionNo()));
        pins.addAll(
                new org.springframework.jdbc.core.JdbcTemplate(inventoryDataSource)
                        .queryForList(
                                "SELECT * FROM inv_execution_retention_claim WHERE retention_no=?"
                                        + " ORDER BY id",
                                lookup.retentionNo()));
        return pins;
    }

    @Test
    void hundredExhaustedRowsCannotStarveActionableRowsAndCompositePaginationHasNoGaps() {
        var request = prepared(false);
        var accepted = current(() -> aggregate.retainV2(request));
        var template = routes.find("MAIN", accepted.routeNo());
        var expected = new java.util.HashSet<Long>();
        var actionable = new java.util.HashSet<Long>();
        // Deliberately unverifiable synthetic historic rows test only physical selection, never
        // owner effects.
        for (int i = 0; i < 102; i++) {
            var row = new ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(null);
            String key = "%064x".formatted(i + 1234567);
            row.setRouteNo(key);
            row.setRetentionNo(key);
            row.setAcceptedIntentNo(key);
            row.setExecutionNo("overflow-fixture-" + i);
            row.setState("RETAIN_PENDING");
            row.setRevision(i < 100 ? Long.MAX_VALUE - 1 : 0L);
            row.setObservationEpoch(i < 100 ? Long.MAX_VALUE - 1 : 0L);
            row.setVersion(i < 100 ? Long.MAX_VALUE - 1 : 0L);
            row.setNextRetryAt(LocalDateTime.of(i < 100 ? 2001 : 2002, 1, 1, 0, 0));
            routes.insert(row);
            expected.add(row.getId());
            if (i >= 100) actionable.add(row.getId());
        }
        var cutoff = LocalDateTime.of(2003, 1, 1, 0, 0);
        var epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
        var first = routes.dueGroup(cutoff, epoch, 0, false, 2);
        assertThat(
                        first.stream()
                                .map(
                                        ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute
                                                ::getId)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(actionable);
        var seen = new java.util.HashSet<Long>();
        var cursor = new ReconcileCursor(epoch.toInstant(ZoneOffset.UTC), 0);
        for (int page = 0; page < 20; page++) {
            var rows =
                    routes.dueGroup(
                            cutoff,
                            LocalDateTime.ofInstant(cursor.nextRetryAt(), ZoneOffset.UTC),
                            cursor.id(),
                            cursor.exhaustedGroup(),
                            7);
            if (rows.isEmpty()) break;
            for (var row : rows) assertThat(seen.add(row.getId())).isTrue();
            var last = rows.getLast();
            cursor =
                    new ReconcileCursor(
                            last.getNextRetryAt().toInstant(ZoneOffset.UTC),
                            last.getId(),
                            last.getRevision() >= Long.MAX_VALUE - 1);
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void malformedRouteDefersOnlyMetadataAndPreservesActualAcceptedOriginalAndPins() {
        var request = prepared(false);
        doAnswer(
                        call -> {
                            throw new IllegalStateException("provider unavailable");
                        })
                .when(platformComponent)
                .retainComponent(any());
        doAnswer(
                        call -> {
                            throw new IllegalStateException("provider unavailable");
                        })
                .when(platformComponent)
                .queryOriginalComponent(any());
        var accepted = current(() -> aggregate.retainV2(request));
        var before = routes.find("MAIN", accepted.routeNo());
        var malformed = new ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute();
        org.springframework.beans.BeanUtils.copyProperties(before, malformed);
        malformed.setRouteJson("{");
        org.mockito.Mockito.doReturn(List.of(malformed))
                .when(routes)
                .dueGroup(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.eq(100));
        var result = coordinator.reconcileAcceptedRoutes(null, 100);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().retryDeferred()).isTrue();
        assertThat(result.receipts()).isEmpty();
        var after = routes.find("MAIN", accepted.routeNo());
        assertThat(after.getRouteJson()).isEqualTo(before.getRouteJson());
        assertThat(after.getObservationsJson()).isEqualTo(before.getObservationsJson());
        assertThat(after.getRouteHash()).isEqualTo(before.getRouteHash());
        assertThat(after.getRequestHash()).isEqualTo(before.getRequestHash());
        assertThat(after.getState()).isEqualTo("RETAIN_PENDING");
        assertThat(after.getNextRetryAt()).isAfter(before.getNextRetryAt());
        assertThat(after.getRevision()).isEqualTo(before.getRevision() + 1);
        assertThat(after.getObservationEpoch()).isEqualTo(before.getObservationEpoch() + 1);
    }

    @Test
    void malformedLeadingRouteCannotAbortObservationOfLaterActualAcceptedRoute() {
        var request = prepared(false);
        var accepted = current(() -> aggregate.retainV2(request));
        var valid = routes.find("MAIN", accepted.routeNo());
        var malformed = new ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute();
        org.springframework.beans.BeanUtils.copyProperties(valid, malformed);
        malformed.setId(valid.getId() + 1000000);
        malformed.setRouteJson("{");
        org.mockito.Mockito.doReturn(List.of(malformed, valid))
                .when(routes)
                .dueGroup(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.eq(100));
        var result = coordinator.reconcileAcceptedRoutes(null, 100);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().retryDeferred()).isFalse();
        assertThat(result.receipts()).hasSize(1);
        assertThat(result.receipts().getFirst().retentionNo()).isEqualTo(request.retentionNo());
        assertThat(result.receipts().getFirst().canStart()).isFalse();
    }

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
        var before =
                new JdbcTemplate(inventoryDataSource)
                        .queryForList(
                                "SELECT id,qty,issued_qty,released_qty FROM inv_reservation_line"
                                        + " WHERE reservation_id=? ORDER BY id",
                                nativeProof.heads().getFirst().reservationId());
        var ref = request.reference();
        var seal = new ExecutionStockRetentionPort.ResourceSealLookup(ref, ref.plannedResourceCommandNo(),
                ref.plannedResourceCommandHash(), ref.planHash(), "cancel-" + sku,
                ExecutionEvidenceContract.hash("TEST_CANCEL", sku));
        assertThat(aggregate.releaseAfterNoEffectV2(new ExecutionStockRetentionPort.ReleaseAfterNoEffect(
                request.lookup(), seal)).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForList(
                                        "SELECT id,qty,issued_qty,released_qty FROM"
                                            + " inv_reservation_line WHERE reservation_id=? ORDER"
                                            + " BY id",
                                        nativeProof.heads().getFirst().reservationId()))
                .isEqualTo(before);
    }

    @Test
    void actualEmptyReserveIsTypedNotApplicableAndDoesNotCreateNativeClaim() {
        var request = prepared(false);
        var receipt = current(() -> aggregate.retainV2(request));
        coordinator.queryPreparationObservation(request.lookup()).validate(request.lookup());
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var observation = receipt.components().getLast();
        assertThat(observation.inventory()).isNull();
        assertThat(observation.notApplicable()).isNotNull();
        observation.notApplicable().validate();
        assertThat(observation.notApplicable().original().expectedNeeds()).isEmpty();
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        Integer.class,
                                        request.retentionNo()))
                .isZero();
    }

    @Test
    void interruptedFirstPlatformPinContinuesOnlyTheAcceptedIntentWithoutHumanReplay() {
        var request = prepared(true);
        doAnswer(
                        invocation -> {
                            throw new IllegalStateException("owner unavailable before first pin");
                        })
                .when(platformComponent)
                .retainComponent(any());
        var partial = current(() -> aggregate.retainV2(request));
        assertThat(partial.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(partial.components().getLast().inventory().proof()).isNotNull();
        var row = routes.find("MAIN", partial.routeNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                                Integer.class,
                                request.retentionNo()))
                .isZero();
        doCallRealMethod().when(platformComponent).retainComponent(any());
        doAnswer(
                        invocation -> {
                            throw new AssertionError("MACHINE cannot authorize or reinstall HUMAN");
                        })
                .when(evidence)
                .authorizeCurrent(any());
        var result = aggregate.continueAcceptedRetentionV2(
                new AcceptedRouteLookup(
                        row.getRouteNo(), row.getRouteHash(), row.getAcceptedIntentNo(), row.getAcceptedIntentHash(),
                        request.lookup()));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_by FROM prd_execution_retention WHERE"
                                        + " retention_no=? LIMIT 1",
                                String.class,
                                request.retentionNo()))
                .isEqualTo("SYSTEM:RETENTION");
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
        assertThat(
                        routes.observe(
                                old,
                                "RETAINED",
                                bytes,
                                retained.receiptHash(),
                                now.plusSeconds(30),
                                now))
                .isOne();
        assertThat(
                        routes.observe(
                                old,
                                "CONFLICT",
                                bytes,
                                retained.receiptHash(),
                                now.plusSeconds(30),
                                now))
                .isZero();
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
        assertThat(routes.find("MAIN", retained.routeNo()).getMirrorOriginalJson())
                .isEqualTo(saved);
        bus.publish("different-" + sku, new ai.neargo.shop.invbridge.InventoryLifecycleCommand(
                ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE, original.orderNo(), List.of(), original.orderNo()));
        assertThat(aggregate.queryOriginalV2(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(routes.find("MAIN", retained.routeNo()).getMirrorOriginalJson())
                .isEqualTo(saved);
    }

    @Test
    void missingHistoricalReserveCannotBeInferredFromRealNativeRowsOrTodaysManagedFlags() {
        var request = prepared(true);
        jdbc.update(
                "DELETE FROM sys_outbox WHERE aggregate_type='INVENTORY' AND aggregate_id=? AND"
                        + " event_type=?",
                request.stockOriginal().orderNo(),
                ai.neargo.shop.invbridge.InvMirrorEvent.RESERVE);
        var result = current(() -> aggregate.retainV2(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(routes.find("MAIN", result.routeNo())).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                                Integer.class,
                                request.retentionNo()))
                .isZero();
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
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                            + " retention_no=? AND issue_observation_hash IS NOT"
                                            + " NULL",
                                        Integer.class,
                                        request.retentionNo()))
                .isZero();
    }

    @Autowired ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier routeSchema;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "route_json",
                "component_roster_json",
                "accepted_intent_json",
                "mirror_original_json"
            })
    void nativeRouteGuardsRejectReplacingAcceptedBytesAndPhysicalDeletion(String column) {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(routeSchema.installation()).isEqualTo(
                ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier.Installation.INSTALLED);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE prd_execution_retention_route SET "
                                                + column
                                                + "='{}' WHERE route_no=?",
                                        retained.routeNo()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM prd_execution_retention_route WHERE"
                                                + " route_no=?",
                                        retained.routeNo()))
                .isInstanceOf(RuntimeException.class);
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
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT created_by FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        String.class,
                                        request.retentionNo()))
                .isEqualTo("SYSTEM:RETENTION");
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
        assertThat(
                        inv.queryForObject(
                                "SELECT issue_observation_hash FROM inv_execution_retention_claim"
                                        + " WHERE retention_no=?",
                                String.class,
                                request.retentionNo()))
                .isNull();
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
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM prd_execution_retention WHERE"
                                            + " retention_no=?",
                                    Integer.class,
                                    request.retentionNo()))
                    .isZero();
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
        doAnswer(
                        invocation -> {
                            Object actual = invocation.callRealMethod();
                            if (once.compareAndSet(true, false)) {
                                jdbc.update(
                                        "UPDATE mch_account SET status='DISABLED' WHERE"
                                                + " mch_account_no=?",
                                        employeeAccount);
                            }
                            return actual;
                        })
                .when(evidence)
                .queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        try {
            assertThatThrownBy(() -> current(() -> aggregate.retainV2(request))).isInstanceOf(BizException.class);
        } finally {
            doCallRealMethod().when(evidence).queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        }
        assertThat(routes.find("MAIN", ExecutionStockRetentionAggregateContract
                .routeNo(request.lookup()))).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                                Integer.class,
                                request.retentionNo()))
                .isZero();
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        Integer.class,
                                        request.retentionNo()))
                .isZero();
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
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        Integer.class,
                                        request.retentionNo()))
                .isZero();
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
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_by FROM prd_execution_retention WHERE"
                                        + " retention_no=? LIMIT 1",
                                String.class,
                                route.original().retentionNo()))
                .isEqualTo("SYSTEM:RETENTION");
        assertThat(
                        new JdbcTemplate(inventoryDataSource)
                                .queryForObject(
                                        "SELECT created_by FROM inv_execution_retention_claim WHERE"
                                                + " retention_no=?",
                                        String.class,
                                        route.original().retentionNo()))
                .isEqualTo("SYSTEM:RETENTION");
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
        doAnswer(
                        invocation -> {
                            var actual = (AcceptedComponentAuthority) invocation.callRealMethod();
                            actual.validate(invocation.getArgument(0));
                            var target = actual.target();
                            if ("TARGET".equals(kind)) {
                                var other =
                                        route.components().stream()
                                                .filter(
                                                        c ->
                                                                c.componentKind()
                                                                        != component
                                                                                .componentKind())
                                                .findFirst()
                                                .orElseThrow();
                                target =
                                        new AcceptedComponentLookup(
                                                route.original(),
                                                route.acceptedIntentNo(),
                                                route.acceptedIntentHash(),
                                                other.componentKind(),
                                                other.componentNo(),
                                                other.componentHash());
                            }
                            return new AcceptedComponentAuthority(
                                            "SCHEMA".equals(kind)
                                                    ? "UNSUPPORTED_AUTHORITY_V9"
                                                    : actual.schema(),
                                            "PURPOSE".equals(kind)
                                                    ? "ISSUE_EXECUTION_STOCK"
                                                    : actual.purpose(),
                                            target,
                                            actual.outcome(),
                                            actual.reason(),
                                            actual.route(),
                                            actual.revision(),
                                            actual.observationEpoch(),
                                            null)
                                    .signed();
                        })
                .when(acceptedIntents)
                .queryAcceptedComponent(any());
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
        assertThat(routes.find("MAIN", route.routeNo()).getAcceptedIntentHash())
                .isEqualTo(route.acceptedIntentHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROOF_DOMAIN", "RECEIPT_SCHEMA"})
    void realHostConsumerRejectsNativeV1SubstitutionIntoOtherwiseActualDualOwnerEvidence(String mutation) {
        var request = prepared(true);
        var retained = current(() -> aggregate.retainV2(request));
        var actualProof = retained.components().getLast().inventory().proof();
        doAnswer(
                        invocation -> {
                            var actual =
                                    (InventoryExecutionStockRetentionPort.ComponentReceipt)
                                            invocation.callRealMethod();
                            actual.proof().validate();
                            var proof = actual.proof();
                            String schema = actual.schema();
                            if ("PROOF_DOMAIN".equals(mutation)) {
                                proof =
                                        new InventoryExecutionStockRetentionPort.InventoryProof(
                                                proof.component(),
                                                proof.acceptedEvidenceHash(),
                                                proof.acceptedAuthorityHash(),
                                                proof.ownerSetHash(),
                                                proof.heads(),
                                                proof.lines(),
                                                proof.outbounds(),
                                                proof.refundClaims(),
                                                proof.returnClaims(),
                                                proof.disposition(),
                                                proof.state(),
                                                proof.revision(),
                                                proof.issueObservationHash(),
                                                ExecutionEvidenceContract.digest(
                                                        "INVENTORY_EXECUTION_PROOF_V1",
                                                        proof,
                                                        "proofHash"));
                            } else schema = "INVENTORY_EXECUTION_RETENTION_RECEIPT_V1";
                            var changed =
                                    new InventoryExecutionStockRetentionPort.ComponentReceipt(
                                            schema,
                                            actual.componentNo(),
                                            actual.componentHash(),
                                            actual.referenceHash(),
                                            actual.requestHash(),
                                            actual.outcome(),
                                            actual.reason(),
                                            proof,
                                            actual.observedAt(),
                                            null);
                            return new InventoryExecutionStockRetentionPort.ComponentReceipt(
                                    schema,
                                    changed.componentNo(),
                                    changed.componentHash(),
                                    changed.referenceHash(),
                                    changed.requestHash(),
                                    changed.outcome(),
                                    changed.reason(),
                                    changed.proof(),
                                    changed.observedAt(),
                                    ExecutionEvidenceContract.digest(
                                            schema, changed, "receiptHash"));
                        })
                .when(inventoryComponent)
                .queryOriginalComponent(any());
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
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                                Integer.class,
                                request.retentionNo()))
                .isEqualTo(result.components().getFirst().platform().proof().ownerSetCount());
    }

    @Test
    void interruptionAfterSecondOwnerBeforeHostCasPreservesBothPinsAndResumesTheSameIntent() {
        var request = prepared(true);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM prd_execution_retention"
                                                            + " WHERE retention_no=?",
                                                    Integer.class,
                                                    request.retentionNo()))
                                    .isPositive();
                            assertThat(
                                            new JdbcTemplate(inventoryDataSource)
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                + " inv_execution_retention_claim"
                                                                + " WHERE retention_no=?",
                                                            Integer.class,
                                                            request.retentionNo()))
                                    .isOne();
                            throw new IllegalStateException(
                                    "interrupted after both owner commits before HOST observation"
                                            + " CAS");
                        })
                .when(routes)
                .observe(any(), any(), any(), any(), any(), any());
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
        doAnswer(
                        invocation -> {
                            throw new IllegalStateException("native acknowledgement unavailable");
                        })
                .when(inventoryComponent)
                .acknowledgeIssued(any());
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
