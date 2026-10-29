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

/** Real prepared Core reference and native Inventory owner; SOURCE ONLY until unified test phase. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@TestPropertySource(properties = {
    "shop.inventory.stock-authority=INVENTORY",
    "spring.main.allow-bean-definition-overriding=false",
    "shop.personnel.publication.archive-only=true", "shop.schedule.publication.archive-only=true",
    "shop.resource.publication.archive-only=true"
})
class NativeInventoryExecutionStockRetentionIT extends ManualSourceHostFixture {
    @Autowired PersonnelService personnel;
    @Autowired ScheduleService schedules;
    @Autowired ServiceExecutionService executionOwner;
    @Autowired ServiceExecutionSchemaVerifier executionSchema;
    @Autowired InventoryExecutionStockRetentionPort retention;
    @Autowired ExecutionStockRetentionSchemaVerifier retentionSchema;
    @MockitoSpyBean ServiceExecutionEvidencePort evidence;
    @Autowired ReservationService reservations;
    @Autowired StockPostingService posting;
    @Autowired OutboundService outbound;
    @Autowired OriginalReservationRestoreService restores;
    @Autowired ReturnReceiptService returns;
    @Autowired StockCountService counts;
    @Autowired OriginalInventoryRestoreAdapter originalReturns;
    @Autowired ExecutionRetentionMapper nativeLinks;
    @MockitoSpyBean ExecutionRetentionClaimMapper nativeClaims;
    @MockitoSpyBean ReservationMapper nativeHeads;
    private String employeeAccount;

    private JdbcTemplate inv() {
        return new JdbcTemplate(inventoryDataSource);
    }

    private InventoryExecutionStockRetentionPort.ComponentLookup prepared() {
        return prepared(1);
    }

    private InventoryExecutionStockRetentionPort.ComponentLookup prepared(int quantity) {
        assertThat(retentionSchema.installation())
                .isEqualTo(ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED);
        var flow = ready(true, quantity);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        assertThat(facts.stockOriginal().authority()).isEqualTo("INVENTORY");
        var original = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
                facts.stockConfirmationOperationNo()).lookup();
        return new InventoryExecutionStockRetentionPort.ComponentLookup(
                InventoryExecutionStockRetentionContract.nativeComponent(original, facts.confirmationNo()));
    }

    private InventoryExecutionStockRetentionPort.ComponentReceipt retain(
            InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        return current(() -> retention.retainComponent(
                new InventoryExecutionStockRetentionPort.ComponentRetain(lookup.component())));
    }

    @Test
    void realNativeTopologyStartsWithDistinctGuardsAndBeanOverridingDisabled() {
        assertThat(web.getEnvironment().getProperty("spring.main.allow-bean-definition-overriding"))
                .isEqualTo("false");
        for (String bean : List.of("inventoryExecutionStockRetentionGuard",
                "inventoryExecutionStockRetentionSchemaVerifier", "executionStockRetentionGuard",
                "executionStockRetentionSchemaVerifier")) {
            assertThat(web.getBean(bean)).isNotNull();
        }
        assertThat(web.getBeansOfType(InventoryExecutionStockRetentionPort.class)).hasSize(1);
        assertThat(web.getBeansOfType(ExecutionStockRetentionPort.class)).hasSize(1);
    }

    @Test
    void nativeAllocatedProofCoversCompleteRealOriginalAndReplayDoesNotChangeDisposition() {
        var lookup = prepared();
        var order = lookup.component().original().stockOriginal().orderNo();
        var before = inventoryEffects(order);
        var receipt = retain(lookup);
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var proof = receipt.proof();
        proof.validate();
        assertThat(proof.component()).isEqualTo(lookup.component());
        assertThat(proof.lines()).hasSize(lookup.component().expectedNeeds().size());
        assertThat(proof.heads()).allSatisfy(head -> {
            assertThat(head.confirmationOperationNo()).isEqualTo(lookup.component().original()
                    .confirmationOperationNo());
            assertThat(head.state()).isEqualTo("ALLOCATED");
        });
        assertThat(proof.outbounds()).isEmpty();
        assertThat(retain(lookup).proof()).isEqualTo(proof);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        assertThat(inventoryEffects(order)).isEqualTo(before);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isEqualTo(proof.lines().size());
    }

    @Test
    void retainedOriginalRejectsAllContraryNativeWritersWithoutPartialEffects() {
        var lookup = prepared();
        var order = lookup.component().original().stockOriginal().orderNo();
        var proof = retain(lookup).proof();
        var head = proof.heads().getFirst();
        var line = proof.lines().getFirst();
        var before = inventoryEffects(order);
        List<Runnable> writers = List.of(
                () -> reservations.commitByRef(order, "TEST"),
                () -> reservations.allocatePaidByRef(order),
                () -> reservations.issueByFulfillmentRef(line.fulfillmentRef(), "TEST"),
                () -> reservations.releaseAllocatedByRef(order),
                () -> reservations.releaseAllocatedByFulfillmentRef(line.fulfillmentRef()),
                () -> reservations.release(head.ownerId(), order),
                () -> reservations.releaseByRef(order),
                () -> reservations.cancelByRef(head.ownerId(), order));
        for (var writer : writers) {
            assertThatThrownBy(writer::run).isInstanceOf(RuntimeException.class);
            assertThat(inventoryEffects(order)).isEqualTo(before);
        }
        assertThat(retention.queryOriginalComponent(lookup).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void consumedProofIncludesRealSaleLedgerAndRejectsVoidPublicReverseAndBothReturnQualities() {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        String fulfillment = lookup.component().expectedNeeds().getFirst().fulfillmentRef();
        reservations.issueByFulfillmentRef(fulfillment, "TEST");
        var receipt = retain(lookup);
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var proof = receipt.proof();
        assertThat(proof.disposition()).isEqualTo("CONSUMED");
        assertThat(proof.outbounds()).isNotEmpty().allSatisfy(out -> {
            assertThat(out.state()).isEqualTo("POSTED");
            assertThat(out.purpose()).isEqualTo("SALE");
            assertThat(out.ledger()).isNotEmpty();
            assertThat(out.reversals()).isEmpty();
        });
        var out = proof.outbounds().getFirst();
        var line = proof.lines().getFirst();
        var before = inventoryEffects(order);
        assertThatThrownBy(() -> outbound.voidOrder(out.ownerId(), out.outboundNo(), "TEST"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> posting.reverse(out.ownerId(), out.outboundNo(), "OUT", "TEST"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> restores.restore("refund-" + sku, order,
                List.of(new OriginalReservationRestoreService.ItemQty(line.itemId(), 1))))
                .isInstanceOf(RuntimeException.class);
        for (var quality : ReturnReceiptService.Quality.values()) {
            assertThatThrownBy(() -> returns.receive("return-" + quality + sku, "case-" + sku, order,
                    fulfillment, List.of(new ReturnReceiptService.Line(line.itemId(), 1, quality)), "TEST"))
                    .isInstanceOf(RuntimeException.class);
        }
        String observation = InventoryExecutionStockRetentionContract.issueObservationHash(lookup.component(),
                proof.heads(), proof.lines(), proof.outbounds());
        assertThat(retention.acknowledgeIssued(new InventoryExecutionStockRetentionPort.ComponentIssued(
                lookup, observation)).proof().issueObservationHash()).isEqualTo(observation);
        assertThat(inventoryEffects(order)).isEqualTo(before);
    }

    @Test
    void priorUnsaleableReturnAndPriorSaleReverseCannotBecomeRetainedConsumedEvidence() {
        var lookup = prepared();
        var need = lookup.component().expectedNeeds().getFirst();
        reservations.issueByFulfillmentRef(need.fulfillmentRef(), "TEST");
        var row = inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?",
                lookup.component().original().stockOriginal().orderNo());
        returns.receive("return-" + sku, "case-" + sku,
                lookup.component().original().stockOriginal().orderNo(), need.fulfillmentRef(),
                List.of(new ReturnReceiptService.Line((String) row.get("item_id"), 1,
                        ReturnReceiptService.Quality.UNSALEABLE)), "TEST");
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isZero();
        assertThatThrownBy(() -> restores.restore("refund-" + sku,
                lookup.component().original().stockOriginal().orderNo(),
                List.of(new OriginalReservationRestoreService.ItemQty((String) row.get("item_id"), 1))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void allocatedStockCannotBeSpentByUnrelatedOutButLegitimateIssueUnholdsItsOwnQuantity() {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        var row = inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", order);
        var owner = (String) row.get("owner_id");
        var item = (String) row.get("item_id");
        var location = (String) row.get("location_id");
        int reserved = inv().queryForObject("SELECT reserved FROM inv_stock_balance"
                + " WHERE owner_id=? AND item_id=? AND location_id=?", Integer.class, owner, item, location);
        inv().update("UPDATE inv_stock_balance SET on_hand=reserved"
                + " WHERE owner_id=? AND item_id=? AND location_id=?", owner, item, location);
        assertThatThrownBy(() -> posting.post(new StockPostingService.PostingDoc(owner, "OUT", "loss-" + sku,
                "COUNT_LOSS", LocalDateTime.now(), "TEST",
                List.of(new StockPostingService.Line(1, item, location, 1, 0L)))))
                .isInstanceOf(RuntimeException.class);
        reservations.issueByFulfillmentRef(lookup.component().expectedNeeds().getFirst().fulfillmentRef(), "TEST");
        assertThat(inv().queryForObject("SELECT on_hand FROM inv_stock_balance"
                + " WHERE owner_id=? AND item_id=? AND location_id=?", Integer.class, owner, item, location))
                .isEqualTo(reserved - ((Number) row.get("qty")).intValue());
    }

    @Test
    void machineObservationWithoutAcceptedIntentDoesNotPinAndV1HostAggregateRemainsPlatformOnly() {
        var lookup = prepared();
        assertThat(retention.queryOriginalComponent(lookup).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.UNKNOWN);
        assertThat(retention.continueAcceptedRetention(new InventoryExecutionStockRetentionPort.AcceptedRetention(
                lookup, "no-real-router", "0".repeat(64))).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.UNKNOWN);
        var proof = retain(lookup).proof();
        var ref = lookup.component().original().reference();
        var seal = new ExecutionStockRetentionPort.ResourceSealLookup(ref, ref.plannedResourceCommandNo(),
                ref.plannedResourceCommandHash(), ref.planHash(), "cancel-" + sku, "0".repeat(64));
        assertThat(retention.releaseAfterNoEffect(new InventoryExecutionStockRetentionPort.ComponentRelease(
                lookup, seal)).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.UNAVAILABLE);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        assertThat(web.getBean(ExecutionStockRetentionPort.class).queryOriginal(lookup.component().original())
                .outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.UNAVAILABLE);
    }
    @Test
    void completeNativeOwnerRosterIncludesEveryPhysicalLineOfAnAggregatedNeed() {
        var lookup = prepared(2);
        String order = lookup.component().original().stockOriginal().orderNo();
        var original = inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", order);
        inv().update("UPDATE inv_reservation_line SET qty=1 WHERE id=?", original.get("id"));
        inv().update("INSERT INTO inv_reservation_line(reservation_id,line_no,owner_id,item_id,location_id,qty,"
                 + "fulfillment_ref,issued_qty,released_qty,source_sku_no,source_store_no)"
                + " VALUES(?,2,?,?,?,1,?,0,0,?,?)",
                original.get("reservation_id"), original.get("owner_id"), original.get("item_id"),
                original.get("location_id"), original.get("fulfillment_ref"), original.get("source_sku_no"),
                original.get("source_store_no"));
        var proof = retain(lookup).proof();
        assertThat(lookup.component().expectedNeeds()).hasSize(1);
        assertThat(proof.lines()).hasSize(2).extracting(line -> line.originalQty()).containsExactly(1, 1);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        assertThat(inv().queryForObject("SELECT owner_set_count FROM inv_execution_retention_claim"
                + " WHERE component_no=?", Integer.class, lookup.component().componentNo())).isEqualTo(2);
    }

    @Test
    void sourceLessLegacyReturnCannotPostAnUnprovenInbound() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        var line = proof.lines().getFirst();
        long before = inv().queryForObject("SELECT COUNT(*) FROM inv_inbound_order WHERE source_ref=?",
                Long.class, "unknown-" + sku);
        assertThatThrownBy(() -> reservations.restore(line.ownerId(), "unknown-" + sku,
                List.of(new ReservationService.Line(line.itemId(), line.locationId(), 1)), "TEST"))
                .isInstanceOf(RuntimeException.class);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_inbound_order WHERE source_ref=?",
                Long.class, "unknown-" + sku)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE_SKU", "SOURCE_STORE", "CONFIRMATION", "MANIFEST", "MISSING_LINE"})
    void actualIncompleteOrChangedOriginalNeverPinsAPrefix(String corruption) {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        switch (corruption) {
            case "SOURCE_SKU" -> inv().update("UPDATE inv_reservation_line l JOIN inv_reservation r"
                    + " ON r.reservation_id=l.reservation_id SET l.source_sku_no='other'"
                    + " WHERE r.external_ref=?", order);
            case "SOURCE_STORE" -> inv().update("UPDATE inv_reservation_line l JOIN inv_reservation r"
                    + " ON r.reservation_id=l.reservation_id SET l.source_store_no='other'"
                    + " WHERE r.external_ref=?", order);
            case "CONFIRMATION" -> inv().update("UPDATE inv_reservation SET confirmation_operation_no='other'"
                    + " WHERE external_ref=?", order);
            case "MANIFEST" -> inv().update("UPDATE inv_reservation SET source_manifest_hash=? WHERE external_ref=?",
                    "f".repeat(64), order);
            case "MISSING_LINE" -> inv().update("DELETE l FROM inv_reservation_line l JOIN inv_reservation r"
                    + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", order);
            default -> throw new IllegalArgumentException(corruption);
        }
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isZero();
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention_claim WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isZero();
    }

    @Test
    void publicReverseBeforeRetainInvalidatesConsumedOriginalEvenWhenLegacyStatusStaysPosted() {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        reservations.issueByFulfillmentRef(lookup.component().expectedNeeds().getFirst().fulfillmentRef(), "TEST");
        var out = inv().queryForMap("SELECT * FROM inv_outbound_order WHERE source_ref=?", order);
        posting.reverse((String) out.get("owner_id"), (String) out.get("outbound_no"), "OUT", "TEST");
        assertThat(out.get("status")).isEqualTo("POSTED");
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
    }

    @Test
    void refundFirstThenPhysicalReturnShareTheSameOriginalCapAndExactRefundReplayHasNoSecondInbound() {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        String fulfillment = lookup.component().expectedNeeds().getFirst().fulfillmentRef();
        reservations.issueByFulfillmentRef(fulfillment, "TEST");
        var line = inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", order);
        var items = List.of(new OriginalReservationRestoreService.ItemQty((String) line.get("item_id"), 1));
        restores.restore("refund-" + sku, order, items);
        var before = inv().queryForList("SELECT * FROM inv_inbound_order WHERE source_ref=?", "refund-" + sku);
        restores.restore("refund-" + sku, order, items);
        assertThat(inv().queryForList("SELECT * FROM inv_inbound_order WHERE source_ref=?", "refund-" + sku))
                .isEqualTo(before).hasSize(1);
        assertThatThrownBy(() -> returns.receive("return-" + sku, "case-" + sku, order, fulfillment,
                List.of(new ReturnReceiptService.Line((String) line.get("item_id"), 1,
                        ReturnReceiptService.Quality.SALEABLE)), "TEST")).isInstanceOf(RuntimeException.class);
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
    }

    @Test
    void currentSelfRevocationAfterGenuineExternalEvidenceIOCannotCreateNativePins() throws Exception {
        var lookup = prepared();
        var actualEvidenceRead = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            Object actual = call.callRealMethod();
            if (once.compareAndSet(true, false)) {
                actualEvidenceRead.countDown();
                await(revoked);
            }
            return actual;
        }).when(evidence).queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var retaining = worker.submit(() -> retain(lookup));
            assertThat(actualEvidenceRead.await(15, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no=?", employeeAccount);
            revoked.countDown();
            assertThatThrownBy(() -> retaining.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
        } finally {
            revoked.countDown();
            doCallRealMethod().when(evidence).queryOriginalEvidence(any(ExecutionEvidenceLookup.class));
        }
        assertNoNativePins(lookup);
    }

    @Test
    void retainedOriginalRacingFulfillmentIssueKeepsStockAndOutboundsUnchanged() throws Exception {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        retain(lookup);
        var before = inventoryEffects(order);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var observed = workers.submit(() -> retention.queryOriginalComponent(lookup));
            var writer = workers.submit(() -> reservations.issueByFulfillmentRef(
                    lookup.component().expectedNeeds().getFirst().fulfillmentRef(), "TEST"));
            assertThat(observed.get(15, TimeUnit.SECONDS).outcome())
                    .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            assertThatThrownBy(() -> writer.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
        }
        assertThat(inventoryEffects(order)).isEqualTo(before);
    }

    @Test
    void retainWinsBeforeAConcurrentWholeOriginalReleaseAndNoQuantityIsReleased() throws Exception {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        var inserted = new CountDownLatch(1);
        var writerAtRoot = new CountDownLatch(1);
        var permitCommit = new CountDownLatch(1);
        var before = inventoryEffects(order);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            inserted.countDown();
            await(permitCommit);
            return result;
        }).when(nativeClaims).insert(any(InvExecutionRetentionClaim.class));
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("native-release-writer")) {
                writerAtRoot.countDown();
            }
            return call.callRealMethod();
        }).when(nativeHeads).lockOriginal(order);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var retaining = workers.submit(() -> retain(lookup));
            assertThat(inserted.await(15, TimeUnit.SECONDS)).isTrue();
            var release = workers.submit(() -> {
                Thread.currentThread().setName("native-release-writer");
                reservations.releaseAllocatedByRef(order);
            });
            assertThat(writerAtRoot.await(15, TimeUnit.SECONDS)).isTrue();
            permitCommit.countDown();
            assertThat(retaining.get(15, TimeUnit.SECONDS).outcome())
                    .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            assertThatThrownBy(() -> release.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
        } finally {
            permitCommit.countDown();
            doCallRealMethod().when(nativeClaims).insert(any(InvExecutionRetentionClaim.class));
            doCallRealMethod().when(nativeHeads).lockOriginal(order);
        }
        assertThat(inventoryEffects(order)).isEqualTo(before);
    }

    @Test
    void releaseWinsBeforeRetainAndNoPartialComponentOrHeaderIsInserted() {
        var lookup = prepared();
        reservations.releaseAllocatedByRef(lookup.component().original().stockOriginal().orderNo());
        assertThat(retain(lookup).outcome()).isNotEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention_claim WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isZero();
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention WHERE component_no=?",
                Long.class, lookup.component().componentNo())).isZero();
    }

    @Test
    void oneRetainedMemberRejectsTheWholeCaseInsensitiveFulfillmentBeforeAnyOtherOriginalChanges() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        var line = proof.lines().getFirst();
        String other = "other-" + sku;
        reservations.reserve(line.ownerId(), other,
                List.of(new ReservationService.Line(line.itemId(), line.locationId(), 1, line.fulfillmentRef())),
                1800, 2);
        reservations.allocatePaidByRef(other);
        var before = inventoryEffects(other);
        assertThatThrownBy(() -> reservations.issueByFulfillmentRef(
                line.fulfillmentRef().toUpperCase(Locale.ROOT), "TEST")).isInstanceOf(BizException.class);
        assertThat(inventoryEffects(other)).isEqualTo(before);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_outbound_order WHERE source_ref=?", Long.class,
                other)).isZero();
        assertThat(retention.queryOriginalComponent(lookup).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void negativeInboundReversalCannotSpendAnotherOriginalsReservedFloor() {
        var lookup = prepared();
        String order = lookup.component().original().stockOriginal().orderNo();
        var line = inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", order);
        String owner = (String) line.get("owner_id");
        String item = (String) line.get("item_id");
        String location = (String) line.get("location_id");
        String inbound = "in-" + sku;
        posting.post(new StockPostingService.PostingDoc(owner, "IN", inbound, "OTHER", LocalDateTime.now(), "TEST",
                List.of(new StockPostingService.Line(1, item, location, 1, 0L))));
        inv().update("UPDATE inv_stock_balance SET on_hand=reserved WHERE owner_id=? AND item_id=? AND location_id=?",
                owner, item, location);
        assertThatThrownBy(() -> posting.reverse(owner, inbound, "IN", "TEST")).isInstanceOf(BizException.class);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE doc_no=?", Long.class, inbound + "-R"))
                .isZero();
    }

    @Test
    void genuineLifecycleOneOwnerCommitsOneSaleBeforeRealCoreNativeConsumedRetention() {
        Object target = AopTestUtils.getUltimateTargetObject(orderOwner);
        Object previous = ReflectionTestUtils.getField(target, "inventoryLifecycleVersion");
        try {
            // This is the actual existing order-owner setting used to create the real CounterOrderPlan.
            ReflectionTestUtils.setField(target, "inventoryLifecycleVersion", 1);
            var lookup = prepared();
            assertThat(lookup.component().original().stockOriginal().lifecycleVersion()).isEqualTo(1);
            var receipt = retain(lookup);
            assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            var proof = receipt.proof();
            assertThat(proof.disposition()).isEqualTo("CONSUMED");
            assertThat(proof.heads()).allSatisfy(head -> {
                assertThat(head.lifecycleVersion()).isEqualTo(1);
                assertThat(head.state()).isEqualTo("COMMITTED");
                assertThat(head.confirmationOperationNo())
                        .isEqualTo(lookup.component().original().confirmationOperationNo());
            });
            assertThat(proof.outbounds()).hasSize(1).allSatisfy(out -> {
                assertThat(out.state()).isEqualTo("POSTED");
                assertThat(out.purpose()).isEqualTo("SALE");
                assertThat(out.ledger()).hasSameSizeAs(out.lines());
                assertThat(out.reversals()).isEmpty();
            });
            assertThat(proof.lines()).allSatisfy(line -> assertThat(line.issuedQty()).isZero());
            assertThat(retain(lookup).proof()).isEqualTo(proof);
        } finally {
            ReflectionTestUtils.setField(target, "inventoryLifecycleVersion", previous);
        }
    }

    @Test
    void directOwnerCommitAndDispositionReleaseAreFencedButExactReserveAndConfirmationReplayRemainObservational() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        var head = proof.heads().getFirst();
        String order = sourceOrder(lookup);
        var before = ownerStockState(order);
        assertThatThrownBy(() -> reservations.commit(head.ownerId(), order, "TEST"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> inventoryDisposition.releaseOriginal(new StockDispositionPort.ReservationLookup(
                lookup.component().original().stockOriginal(), StockDispositionContract.reservationOperationNo(
                        lookup.component().original().stockOriginal())))).isInstanceOf(BizException.class);
        assertThat(reservations.reserve(head.ownerId(), order, sourceLines(lookup), 1800, 2))
                .isEqualTo(head.reservationId());
        var changed = new ArrayList<>(sourceLines(lookup));
        var line = changed.getFirst();
        changed.set(0, new ReservationService.Line(line.itemId(), line.locationId(), line.qty() + 1,
                line.fulfillmentRef(), line.sourceSkuNo(), line.sourceStoreNo()));
        assertThatThrownBy(() -> reservations.reserve(head.ownerId(), order, changed, 1800, 2))
                .isInstanceOf(BizException.class);
        assertThat(inventoryDisposition.confirmOriginal(new StockDispositionPort.Confirmation(
                lookup.component().original().stockOriginal(), lookup.component().original().confirmationOperationNo()))
                .outcome()).isEqualTo(StockDispositionPort.Outcome.APPLIED);
        assertThat(reservations.expireOverdue(200)).isGreaterThanOrEqualTo(0);
        assertThat(ownerStockState(order)).isEqualTo(before);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
    }

    @Test
    void expiryCannotMutateAProtectedOriginalEvenIfItsHeadWasCorruptedBackToOverdueHeld() {
        var lookup = prepared();
        retain(lookup);
        inv().update("UPDATE inv_reservation SET status='HELD',expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)"
                + " WHERE external_ref=?", sourceOrder(lookup));
        var before = ownerStockState(sourceOrder(lookup));
        assertThatThrownBy(() -> reservations.expireOverdue(200)).isInstanceOf(BizException.class);
        assertThat(ownerStockState(sourceOrder(lookup))).isEqualTo(before);
    }

    @Test
    void frozenNativeMappingAndExactRetrySurviveCurrentAclAndSharedBalanceVersionChanges() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        var line = proof.lines().getFirst();
        inv().update("UPDATE inv_item_ref SET ref=? WHERE owner_id=? AND ref_system='AISHOP' AND ref=?",
                "history-" + sku, line.ownerId(), sku);
        posting.post(new StockPostingService.PostingDoc(line.ownerId(), "IN", "other-in-" + sku, "OTHER",
                LocalDateTime.now(), "TEST", List.of(new StockPostingService.Line(1, line.itemId(), line.locationId(),
                        1, 0L))));
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        assertThat(retain(lookup).proof()).isEqualTo(proof);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROW", "LEDGER", "LATE_REFUND", "ORPHAN_LINK"})
    void postPinNativeRowLedgerClaimAndHeaderAssociationDriftCannotReuseAnOldSuccessfulProof(String kind) {
        var lookup = prepared();
        if ("LEDGER".equals(kind) || "LATE_REFUND".equals(kind)) {
            reservations.issueByFulfillmentRef(lookup.component().expectedNeeds().getFirst().fulfillmentRef(), "TEST");
        }
        var proof = retain(lookup).proof();
        var line = proof.lines().getFirst();
        switch (kind) {
            case "ROW" -> inv().update("UPDATE inv_reservation_line SET fulfillment_ref='drift' WHERE id=?",
                    line.originalLineId());
            case "LEDGER" -> inv().update("UPDATE inv_ledger SET reason_code='OTHER' WHERE id=?",
                    proof.outbounds().getFirst().ledger().getFirst().id());
            case "LATE_REFUND" -> inv().update("INSERT INTO inv_refund_restore(purpose_no,original_line_id,qty,"
                    + "created_by) VALUES(?,?,1,'DRIFT_FIXTURE')", "late-" + sku, line.originalLineId());
            case "ORPHAN_LINK" -> {
                var original = nativeLinks.selectList(Wrappers
                        .<InvExecutionRetention>lambdaQuery().eq(InvExecutionRetention::getComponentNo,
                                lookup.component().componentNo())).getFirst();
                original.setId(null);
                original.setComponentNo("f".repeat(64));
                original.setRetentionNo("e".repeat(64));
                original.setExecutionNo("orphan-" + sku);
                assertThat(nativeLinks.insert(original)).isEqualTo(1);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        assertThat(retention.queryOriginalComponent(lookup).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
    }

    @Test
    void permanentOwnerHeaderAndDuplicateLineAssociationsAreProtectedByActualNativeConstraints() {
        var lookup = prepared();
        retain(lookup);
        assertThatThrownBy(() -> inv().update("DELETE FROM inv_execution_retention_claim WHERE component_no=?",
                lookup.component().componentNo())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> inv().update("UPDATE inv_execution_retention_claim SET reference_hash=?"
                + " WHERE component_no=?", "f".repeat(64), lookup.component().componentNo()))
                .isInstanceOf(RuntimeException.class);
        var row = nativeLinks.selectList(Wrappers
                .<InvExecutionRetention>lambdaQuery().eq(InvExecutionRetention::getComponentNo,
                        lookup.component().componentNo())).getFirst();
        row.setId(null);
        assertThatThrownBy(() -> nativeLinks.insert(row)).isInstanceOf(RuntimeException.class);
        assertThat(retention.queryOriginalComponent(lookup).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void realCountAdjustmentAndAnEarlierSuccessfulLineBothRollBackAtTheReservedFloor() {
        var lookup = prepared();
        var row = nativeRow(lookup);
        String owner = (String) row.get("owner_id");
        String item = (String) row.get("item_id");
        String location = (String) row.get("location_id");
        var before = ownerStockState(sourceOrder(lookup));
        var countHeads = inv().queryForList("SELECT * FROM inv_stock_count WHERE owner_id=? ORDER BY id", owner);
        assertThatThrownBy(() -> counts.adjustOne(owner, location, item, 0, "COUNT_LOSS", "TEST"))
                .isInstanceOf(RuntimeException.class);
        assertThat(ownerStockState(sourceOrder(lookup))).isEqualTo(before);
        assertThat(inv().queryForList("SELECT * FROM inv_stock_count WHERE owner_id=? ORDER BY id", owner))
                .isEqualTo(countHeads);
        String extra = "extra-" + sku;
        inv().update("INSERT INTO inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved) VALUES(?,?,?,5,0)",
                owner, extra, location);
        int onHand = inv().queryForObject("SELECT on_hand FROM inv_stock_balance"
                + " WHERE owner_id=? AND item_id=? AND location_id=?", Integer.class, owner, item, location);
        assertThatThrownBy(() -> posting.post(new StockPostingService.PostingDoc(owner, "OUT", "mixed-" + sku,
                "COUNT_LOSS", LocalDateTime.now(), "TEST", List.of(
                        new StockPostingService.Line(1, extra, location, 1, 0L),
                        new StockPostingService.Line(2, item, location, onHand, 0L)))))
                .isInstanceOf(BizException.class);
        assertThat(inv().queryForObject("SELECT on_hand FROM inv_stock_balance"
                + " WHERE owner_id=? AND item_id=? AND location_id=?", Integer.class, owner, extra, location))
                .isEqualTo(5);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE doc_no=?", Long.class,
                "mixed-" + sku)).isZero();
    }

    @Test
    void underspecifiedDualEnumCannotEnterTheNativeV1SchemaOrReachAnyNativeMutation() {
        var lookup = prepared();
        var parent = lookup.component().original().stockOriginal();
        var dualOriginal = new StockDispositionPort.Original(parent.tenantNo(), parent.entityNo(), parent.orderNo(),
                "DUAL", parent.lifecycleVersion(), parent.manifestHash(), parent.needs());
        var dualLookup = ExecutionStockRetentionContract.request(lookup.component().original().reference(),
                dualOriginal, StockDispositionContract.operationNo(dualOriginal)).lookup();
        var descriptor = new InventoryExecutionStockRetentionPort.ComponentDescriptor(
                "INVENTORY_EXECUTION_COMPONENT_V1", "d".repeat(64), dualLookup,
                InventoryExecutionStockRetentionPort.ProofMode.DUAL_MIRROR, dualOriginal.needs(),
                dualOriginal.manifestHash(), dualOriginal.manifestHash(), lookup.component().sourceConfirmationNo(),
                null);
        descriptor = new InventoryExecutionStockRetentionPort.ComponentDescriptor(descriptor.schema(),
                descriptor.componentNo(), descriptor.original(), descriptor.proofMode(), descriptor.expectedNeeds(),
                descriptor.parentManifestHash(), descriptor.componentManifestHash(), descriptor.sourceConfirmationNo(),
                ExecutionEvidenceContract.digest("INVENTORY_EXECUTION_COMPONENT_V1", descriptor, "componentHash"));
        var rejected = descriptor;
        var before = ownerStockState(sourceOrder(lookup));
        assertThatThrownBy(rejected::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retention.retainComponent(
                new InventoryExecutionStockRetentionPort.ComponentRetain(rejected)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ownerStockState(sourceOrder(lookup))).isEqualTo(before);
        assertNoNativePins(lookup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "COMMIT_BY_REF", "ALLOCATE", "ISSUE", "RELEASE", "RELEASE_FULFILLMENT",
            "RELEASE_HELD_API", "RELEASE_BY_REF", "CANCEL", "RESERVE_APPEND", "REFUND",
            "RETURN_SALEABLE", "RETURN_UNSALEABLE", "REVERSE", "VOID", "RESERVE_MISMATCH", "RESERVE_REPLAY",
            "CONFIRM_REPLAY", "DISPOSITION_RELEASE"})
    void firstNativePinWinsTheGatedContraryWriterRaceWithoutStockEffects(String action) throws Exception {
        var lookup = preparedForWriter(action);
        String order = sourceOrder(lookup);
        Runnable writer = sourceWriter(action, lookup);
        var before = ownerStockState(order);
        var pinnedButUncommitted = new CountDownLatch(1);
        var writerAtRoot = new CountDownLatch(1);
        var commitPin = new CountDownLatch(1);
        doAnswer(call -> {
            Object value = call.callRealMethod();
            pinnedButUncommitted.countDown();
            await(commitPin);
            return value;
        }).when(nativeClaims).insert(any(InvExecutionRetentionClaim.class));
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("matrix-writer")) {
                writerAtRoot.countDown();
            }
            return call.callRealMethod();
        }).when(nativeHeads).lockOriginal(any(String.class));
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("matrix-writer")) {
                writerAtRoot.countDown();
            }
            return call.callRealMethod();
        }).when(nativeHeads).lockByFulfillment(any(String.class));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var pin = workers.submit(() -> retain(lookup));
            assertThat(pinnedButUncommitted.await(15, TimeUnit.SECONDS)).isTrue();
            var change = workers.submit(() -> {
                Thread.currentThread().setName("matrix-writer");
                writer.run();
            });
            assertThat(writerAtRoot.await(15, TimeUnit.SECONDS)).isTrue();
            commitPin.countDown();
            assertThat(pin.get(15, TimeUnit.SECONDS).outcome())
                    .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
            if (List.of("RESERVE_REPLAY", "CONFIRM_REPLAY").contains(action)) {
                change.get(15, TimeUnit.SECONDS);
            } else {
                assertThatThrownBy(() -> change.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
            }
            assertThat(ownerStockState(order)).isEqualTo(before);
        } finally {
            commitPin.countDown();
            resetNativeRaceSpies();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "COMMIT_BY_REF", "ALLOCATE", "ISSUE", "RELEASE", "RELEASE_FULFILLMENT",
            "RELEASE_HELD_API", "RELEASE_BY_REF", "CANCEL", "RESERVE_APPEND", "REFUND",
            "RETURN_SALEABLE", "RETURN_UNSALEABLE", "REVERSE", "VOID", "RESERVE_MISMATCH", "RESERVE_REPLAY",
            "CONFIRM_REPLAY", "DISPOSITION_RELEASE"})
    void actualWriterWinsItsNativeRootBeforeTheConcurrentFirstPin(String action) throws Exception {
        var lookup = preparedForWriter(action);
        Runnable writer = sourceWriter(action, lookup);
        var writerOwnsRoots = new CountDownLatch(1);
        var readerAtRoot = new CountDownLatch(1);
        var finishWriter = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("matrix-reader")) {
                readerAtRoot.countDown();
            }
            Object value = call.callRealMethod();
            if (Thread.currentThread().getName().equals("matrix-writer") && once.compareAndSet(true, false)) {
                writerOwnsRoots.countDown();
                await(finishWriter);
            }
            return value;
        }).when(nativeHeads).lockOriginal(any(String.class));
        doAnswer(call -> {
            Object value = call.callRealMethod();
            if (Thread.currentThread().getName().equals("matrix-writer") && once.compareAndSet(true, false)) {
                writerOwnsRoots.countDown();
                await(finishWriter);
            }
            return value;
        }).when(nativeHeads).lockByFulfillment(any(String.class));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var change = workers.submit(() -> {
                Thread.currentThread().setName("matrix-writer");
                writer.run();
            });
            assertThat(writerOwnsRoots.await(15, TimeUnit.SECONDS)).isTrue();
            var pin = workers.submit(() -> {
                Thread.currentThread().setName("matrix-reader");
                return retain(lookup);
            });
            assertThat(readerAtRoot.await(15, TimeUnit.SECONDS)).isTrue();
            finishWriter.countDown();
            if (List.of("COMMIT", "COMMIT_BY_REF", "RESERVE_MISMATCH").contains(action)) {
                assertThatThrownBy(() -> change.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
            } else {
                change.get(15, TimeUnit.SECONDS);
            }
            var observed = pin.get(15, TimeUnit.SECONDS);
            if (List.of("RELEASE", "RELEASE_FULFILLMENT", "REFUND", "RETURN_SALEABLE", "RETURN_UNSALEABLE",
                    "REVERSE", "VOID", "RESERVE_APPEND").contains(action)) {
                assertThat(observed.outcome()).isNotEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
                assertNoNativePins(lookup);
            } else {
                assertThat(observed.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
                if ("ISSUE".equals(action)) {
                    assertThat(observed.proof().disposition()).isEqualTo("CONSUMED");
                }
            }
        } finally {
            finishWriter.countDown();
            resetNativeRaceSpies();
        }
    }

    private InventoryExecutionStockRetentionPort.ComponentLookup preparedForWriter(String action) {
        var lookup = prepared();
        if ("RESERVE_APPEND".equals(action)) {
            seedAppendOwner();
        }
        if (List.of("REFUND", "RETURN_SALEABLE", "RETURN_UNSALEABLE", "REVERSE", "VOID").contains(action)) {
            reservations.issueByFulfillmentRef(lookup.component().expectedNeeds().getFirst().fulfillmentRef(), "TEST");
        }
        return lookup;
    }

    private Runnable sourceWriter(String action, InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        String order = sourceOrder(lookup);
        var row = nativeRow(lookup);
        String owner = (String) row.get("owner_id");
        String fulfillment = (String) row.get("fulfillment_ref");
        String item = (String) row.get("item_id");
        return switch (action) {
            case "COMMIT" -> () -> reservations.commit(owner, order, "TEST");
            case "COMMIT_BY_REF" -> () -> reservations.commitByRef(order, "TEST");
            case "ALLOCATE" -> () -> reservations.allocatePaidByRef(order);
            case "ISSUE" -> () -> reservations.issueByFulfillmentRef(fulfillment, "TEST");
            case "RELEASE" -> () -> reservations.releaseAllocatedByRef(order);
            case "RELEASE_FULFILLMENT" -> () -> reservations.releaseAllocatedByFulfillmentRef(fulfillment);
            case "RELEASE_HELD_API" -> () -> reservations.release(owner, order);
            case "RELEASE_BY_REF" -> () -> reservations.releaseByRef(order);
            case "CANCEL" -> () -> reservations.cancelByRef(owner, order);
            case "RESERVE_APPEND" -> () -> reservations.reserve("X" + sku, order,
                    List.of(new ReservationService.Line("X" + sku, "X" + sku, 1, fulfillment, sku, store)), 1800, 2);
            case "REFUND" -> () -> restores.restore("matrix-refund-" + sku, order,
                    List.of(new OriginalReservationRestoreService.ItemQty(item, 1)));
            case "RETURN_SALEABLE", "RETURN_UNSALEABLE" -> () -> returns.receive("matrix-return-" + sku,
                    "matrix-case-" + sku, order, fulfillment, List.of(new ReturnReceiptService.Line(item, 1,
                            "RETURN_SALEABLE".equals(action) ? ReturnReceiptService.Quality.SALEABLE
                                    : ReturnReceiptService.Quality.UNSALEABLE)), "TEST");
            case "REVERSE" -> () -> posting.reverse(owner, (String) row.get("outbound_no"), "OUT", "TEST");
            case "VOID" -> () -> outbound.voidOrder(owner, (String) row.get("outbound_no"), "TEST");
            case "RESERVE_REPLAY" -> () -> reservations.reserve(owner, order, sourceLines(lookup), 1800, 2);
            case "RESERVE_MISMATCH" -> () -> reservations.reserve(owner, order, List.of(new ReservationService.Line(
                    item, (String) row.get("location_id"), ((Number) row.get("qty")).intValue() + 1, fulfillment,
                    (String) row.get("source_sku_no"), (String) row.get("source_store_no"))), 1800, 2);
            case "CONFIRM_REPLAY" -> () -> inventoryDisposition.confirmOriginal(new StockDispositionPort.Confirmation(
                    lookup.component().original().stockOriginal(),
                    lookup.component().original().confirmationOperationNo()));
            case "DISPOSITION_RELEASE" -> () -> inventoryDisposition.releaseOriginal(
                    new StockDispositionPort.ReservationLookup(lookup.component().original().stockOriginal(),
                            StockDispositionContract.reservationOperationNo(
                                    lookup.component().original().stockOriginal())));
            default -> throw new IllegalArgumentException(action);
        };
    }

    private void resetNativeRaceSpies() {
        doCallRealMethod().when(nativeClaims).insert(any(InvExecutionRetentionClaim.class));
        doCallRealMethod().when(nativeHeads).lockOriginal(any(String.class));
        doCallRealMethod().when(nativeHeads).lockByFulfillment(any(String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND_FIRST", "RETURN_FIRST"})
    void savedAfterSaleFrozenSourceAndPhysicalReplayShareAFractionalOriginalCap(String orderOfClaims) {
        var lookup = prepared(2);
        String order = sourceOrder(lookup);
        var row = nativeRow(lookup);
        String fulfillment = (String) row.get("fulfillment_ref");
        String item = (String) row.get("item_id");
        reservations.issueByFulfillmentRef(fulfillment, "TEST");
        String afterSale = "saved-" + sku;
        saveAfterSale(afterSale, order, fulfillment);
        inv().update("UPDATE inv_item_ref SET ref=? WHERE owner_id=? AND ref_system='AISHOP' AND ref=?",
                "history-" + sku, row.get("owner_id"), sku);
        var returned = List.of(new StockPort.SkuQty(sku, 1, store, fulfillment, 2));
        Runnable refund = () -> originalReturns.restoreLegacy(afterSale, returned);
        Runnable physical = () -> returns.receive("physical-" + sku, afterSale, order, fulfillment,
                List.of(new ReturnReceiptService.Line(item, 1, ReturnReceiptService.Quality.SALEABLE)), "TEST");
        if ("REFUND_FIRST".equals(orderOfClaims)) {
            refund.run();
            physical.run();
        } else {
            physical.run();
            refund.run();
        }
        var balances = ownerStockState(order);
        var inbounds = inv().queryForList("SELECT * FROM inv_inbound_order WHERE source_ref IN (?,?) ORDER BY id",
                afterSale, "physical-" + sku);
        refund.run();
        physical.run();
        assertThat(ownerStockState(order)).isEqualTo(balances);
        assertThat(inv().queryForList("SELECT * FROM inv_inbound_order WHERE source_ref IN (?,?) ORDER BY id",
                afterSale, "physical-" + sku)).isEqualTo(inbounds).hasSize(2);
        long originalLine = ((Number) row.get("id")).longValue();
        assertThat(inv().queryForObject("SELECT SUM(qty) FROM inv_refund_restore WHERE original_line_id=?",
                Integer.class, originalLine)).isEqualTo(1);
        assertThat(inv().queryForObject("SELECT SUM(qty) FROM inv_return_receipt WHERE original_line_id=?",
                Integer.class, originalLine)).isEqualTo(1);
        assertThatThrownBy(() -> restores.restore("too-much-" + sku, order,
                List.of(new OriginalReservationRestoreService.ItemQty(item, 1)))).isInstanceOf(RuntimeException.class);
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertNoNativePins(lookup);
    }

    @Test
    void aSaleableFractionAlreadyReturnedStillRejectsANewWholeOriginalPin() {
        var lookup = prepared(2);
        var row = nativeRow(lookup);
        String fulfillment = (String) row.get("fulfillment_ref");
        reservations.issueByFulfillmentRef(fulfillment, "TEST");
        returns.receive("partial-" + sku, "partial-case-" + sku, sourceOrder(lookup), fulfillment,
                List.of(new ReturnReceiptService.Line((String) row.get("item_id"), 1,
                        ReturnReceiptService.Quality.SALEABLE)), "TEST");
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertNoNativePins(lookup);
    }

    @Test
    void savedAfterSaleCannotRestoreAnAmbiguousFrozenSkuToMultipleNativeItems() {
        var lookup = prepared();
        var row = nativeRow(lookup);
        String owner = (String) row.get("owner_id");
        String location = (String) row.get("location_id");
        String otherItem = "amb-" + sku;
        inv().update("INSERT INTO inv_item(item_id,owner_id,name) VALUES(?,?,?)", otherItem, owner, otherItem);
        inv().update("INSERT INTO inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved) VALUES(?,?,?,4,0)",
                owner, otherItem, location);
        String historicalOrder = "historical-" + sku;
        String fulfillment = "historical-f-" + sku;
        reservations.reserve(owner, historicalOrder, List.of(
                new ReservationService.Line((String) row.get("item_id"), location, 1, fulfillment, sku, store),
                new ReservationService.Line(otherItem, location, 1, fulfillment, sku, store)), 1800, 2);
        reservations.allocatePaidByRef(historicalOrder);
        reservations.issueByFulfillmentRef(fulfillment, "TEST");
        String afterSale = "ambiguous-" + sku;
        saveAfterSale(afterSale, historicalOrder, fulfillment);
        var before = ownerStockState(historicalOrder);
        assertThatThrownBy(() -> originalReturns.restoreLegacy(afterSale,
                List.of(new StockPort.SkuQty(sku, 1, store, fulfillment, 2))))
                .isInstanceOf(RuntimeException.class);
        assertThat(ownerStockState(historicalOrder)).isEqualTo(before);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_inbound_order WHERE source_ref=?", Long.class,
                afterSale)).isZero();
    }

    @Test
    void absentSavedAfterSaleSourceRemainsUnavailableRatherThanUsingTodaysAcl() {
        var lookup = prepared();
        var before = ownerStockState(sourceOrder(lookup));
        assertThatThrownBy(() -> originalReturns.restoreLegacy("missing-" + sku,
                List.of(new StockPort.SkuQty(sku, 1, store)))).isInstanceOf(IllegalStateException.class);
        assertThat(ownerStockState(sourceOrder(lookup))).isEqualTo(before);
    }

    @Test
    void retainedOriginalCannotAppendAnotherActualOwnerAndWrongOwnerCannotBecomeAFullNativeOriginal() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        String owner = "X" + sku;
        String location = "X" + sku;
        String item = "X" + sku;
        inv().update("INSERT INTO inv_owner(owner_id,name,external_ref) VALUES(?,?,?)", owner, owner,
                "other-" + entity);
        inv().update("INSERT INTO inv_location(location_id,owner_id,name) VALUES(?,?,?)", location, owner, location);
        inv().update("INSERT INTO inv_item(item_id,owner_id,name) VALUES(?,?,?)", item, owner, item);
        inv().update("INSERT INTO inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved) VALUES(?,?,?,4,0)",
                owner, item, location);
        var append = List.of(new ReservationService.Line(item, location, 1,
                lookup.component().expectedNeeds().getFirst().fulfillmentRef(), sku, store));
        assertThatThrownBy(() -> reservations.reserve(owner, sourceOrder(lookup), append, 1800, 2))
                .isInstanceOf(BizException.class).hasMessage("CONFLICT");
        assertThat(inv().queryForObject("SELECT reserved FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                + " AND location_id=?", Integer.class, owner, item, location)).isZero();
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        // V1 uk_owner_ext forbids a second native owner for the same entity; do not fake that roster.
        assertThatThrownBy(() -> inv().update("INSERT INTO inv_owner(owner_id,name,external_ref) VALUES(?,?,?)",
                "Y" + sku, "second", entity)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void validChangedSourceConfirmationAndPreparedRevisionCannotAdoptAnExistingPermanentExecution() {
        var lookup = prepared();
        var proof = retain(lookup).proof();
        var changed = InventoryExecutionStockRetentionContract.nativeComponent(lookup.component().original(),
                "changed-confirmation");
        assertThat(retain(new InventoryExecutionStockRetentionPort.ComponentLookup(changed)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        var ref = lookup.component().original().reference();
        var changedRef = new ExecutionReference(ref.schema(), ref.scope(), ref.sourceKind(), ref.bindingSchema(),
                ref.executionNo(), ref.grantNo(), ref.unitNo(), ref.taskNo(), ref.workOrderNo(), ref.attemptNo(),
                ref.preparedGrantRevision() + 1, ref.bindingHash(), ref.payloadHash(), ref.originalBindingBytesHash(),
                ref.sourceIdentityHash(), ref.sourceIntentHash(), ref.sourceFenceNo(), ref.sourceFenceRevision(),
                ref.planHash(), ref.plannedResourceCommandNo(), ref.plannedResourceCommandHash(), ref.prepareActionNo(),
                ref.prepareActionHash(), ref.prepareKeyHash(), null).signed();
        var changedRequest = ExecutionStockRetentionContract.request(changedRef,
                lookup.component().original().stockOriginal(), lookup.component().original().confirmationOperationNo());
        var changedComponent = InventoryExecutionStockRetentionContract.nativeComponent(changedRequest.lookup(),
                lookup.component().sourceConfirmationNo());
        assertThatThrownBy(() -> retain(new InventoryExecutionStockRetentionPort.ComponentLookup(changedComponent)))
                .isInstanceOf(BizException.class);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(proof);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention_claim WHERE execution_no=?",
                Long.class, ref.executionNo())).isEqualTo(1);
    }

    private void saveAfterSale(String afterSale, String order, String fulfillment) {
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                + "reason,stock_restored,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,'RETURN_REFUND','REFUNDED','native saved source fixture',0,NOW(),NOW())",
                afterSale, fulfillment, order, buyer, entity);
    }

    @Test
    void mixedFulfillmentIssueBeforeTheFirstPinCommitsAllItsActualMembersAndNativeConsumedProof() {
        var lookup = prepared();
        var row = nativeRow(lookup);
        String other = "mixed-first-" + sku;
        String fulfillment = (String) row.get("fulfillment_ref");
        reservations.reserve((String) row.get("owner_id"), other,
                List.of(new ReservationService.Line((String) row.get("item_id"), (String) row.get("location_id"),
                        1, fulfillment)), 1800, 2);
        reservations.allocatePaidByRef(other);
        reservations.issueByFulfillmentRef(fulfillment.toUpperCase(Locale.ROOT), "TEST");
        assertThat(inv().queryForObject("SELECT status FROM inv_reservation WHERE external_ref=?", String.class,
                other)).isEqualTo("COMMITTED");
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_outbound_order WHERE source_ref=?", Long.class,
                other)).isEqualTo(1);
        var receipt = retain(lookup);
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(receipt.proof().disposition()).isEqualTo("CONSUMED");
    }

    @Test
    void aMutatedReturnedProofDigestCannotBeValidatedAsTheActualNativeOwnerProof() {
        var lookup = prepared();
        var actual = retain(lookup).proof();
        var changed = new InventoryExecutionStockRetentionPort.InventoryProof(actual.component(),
                actual.acceptedEvidenceHash(), actual.acceptedAuthorityHash(), actual.ownerSetHash(), actual.heads(),
                actual.lines(), actual.outbounds(), actual.refundClaims(), actual.returnClaims(), actual.disposition(),
                actual.state(), actual.revision(), actual.issueObservationHash(), "f".repeat(64));
        assertThatThrownBy(changed::validate).isInstanceOf(IllegalArgumentException.class);
        assertThat(retention.queryOriginalComponent(lookup).proof()).isEqualTo(actual);
    }

    @Test
    void unrelatedRealNonSaleOutboundReversalKeepsItsExistingOwnerBehavior() {
        var lookup = prepared();
        var row = nativeRow(lookup);
        String owner = (String) row.get("owner_id");
        String location = (String) row.get("location_id");
        String item = (String) row.get("item_id");
        int before = inv().queryForObject("SELECT on_hand FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                + " AND location_id=?", Integer.class, owner, item, location);
        String document = outbound.postDirectly(new OutboundService.Draft(owner, location, "OTHER",
                "unrelated-" + sku, null, "OTHER", LocalDateTime.now(), "native non-SALE owner",
                List.of(new OutboundService.Line(item, 1, null))), "TEST");
        posting.reverse(owner, document, "OUT", "TEST");
        assertThat(inv().queryForObject("SELECT on_hand FROM inv_stock_balance WHERE owner_id=? AND item_id=?"
                + " AND location_id=?", Integer.class, owner, item, location)).isEqualTo(before);
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void malformedOrDuplicateExistingOriginalClaimsCannotProduceAValidNewNativePin() {
        var lookup = prepared();
        var row = nativeRow(lookup);
        reservations.issueByFulfillmentRef((String) row.get("fulfillment_ref"), "TEST");
        String purpose = "bad-claim-" + sku;
        inv().update("INSERT INTO inv_refund_restore(purpose_no,original_line_id,qty,created_by)"
                + " VALUES(?,?,0,'DRIFT_FIXTURE')", purpose, row.get("id"));
        assertThatThrownBy(() -> inv().update("INSERT INTO inv_refund_restore"
                + "(purpose_no,original_line_id,qty,created_by) VALUES(?,?,1,'DRIFT_FIXTURE')", purpose, row.get("id")))
                .isInstanceOf(RuntimeException.class);
        assertThat(retain(lookup).outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertNoNativePins(lookup);
    }

    private void seedAppendOwner() {
        String owner = "X" + sku;
        inv().update("INSERT INTO inv_owner(owner_id,name,external_ref) VALUES(?,?,?)", owner, owner,
                "other-" + entity);
        inv().update("INSERT INTO inv_location(location_id,owner_id,name) VALUES(?,?,?)", owner, owner, owner);
        inv().update("INSERT INTO inv_item(item_id,owner_id,name) VALUES(?,?,?)", owner, owner, owner);
        inv().update("INSERT INTO inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved) VALUES(?,?,?,4,0)",
                owner, owner, owner);
    }

    private String sourceOrder(InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        return lookup.component().original().stockOriginal().orderNo();
    }

    private Map<String, Object> nativeRow(InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        return inv().queryForMap("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=?", sourceOrder(lookup));
    }

    private List<ReservationService.Line> sourceLines(InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        return inv().queryForList("SELECT l.* FROM inv_reservation_line l JOIN inv_reservation r"
                 + " ON r.reservation_id=l.reservation_id WHERE r.external_ref=? ORDER BY l.line_no",
                sourceOrder(lookup))
                .stream().map(row -> new ReservationService.Line((String) row.get("item_id"),
                        (String) row.get("location_id"), ((Number) row.get("qty")).intValue(),
                        (String) row.get("fulfillment_ref"), (String) row.get("source_sku_no"),
                        (String) row.get("source_store_no"))).toList();
    }

    private List<Object> ownerStockState(String order) {
        var heads = inv().queryForList("SELECT * FROM inv_reservation WHERE external_ref=? ORDER BY id", order);
        String owner = (String) heads.getFirst().get("owner_id");
        return List.of(heads, inventoryEffects(order),
                inv().queryForList("SELECT * FROM inv_stock_balance WHERE owner_id=? ORDER BY id", owner),
                inv().queryForList("SELECT * FROM inv_outbound_order WHERE source_ref=? ORDER BY id", order),
                inv().queryForList("SELECT * FROM inv_ledger WHERE owner_id=? ORDER BY id", owner),
                inv().queryForList("SELECT f.* FROM inv_refund_restore f JOIN inv_reservation_line l"
                        + " ON f.original_line_id=l.id WHERE l.owner_id=? ORDER BY f.id", owner),
                inv().queryForList("SELECT * FROM inv_return_receipt WHERE original_order_no=? ORDER BY id", order));
    }

    private void assertNoNativePins(InventoryExecutionStockRetentionPort.ComponentLookup lookup) {
        for (String table : List.of("inv_execution_retention", "inv_execution_retention_claim")) {
            assertThat(inv().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE reference_hash=?", Long.class,
                    lookup.component().original().reference().referenceHash())).isZero();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("native owner race gate timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private <T> T current(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, work);
    }

    private Flow ready(boolean guestParty) {
        return ready(guestParty, 1);
    }

    private Flow ready(boolean guestParty, int quantity) {
        assertThat(executionSchema.installation()).isEqualTo(ServiceExecutionSchemaVerifier.Installation.INSTALLED);
        assertThat(AopUtils.getTargetClass(executionOwner).getName())
                .isEqualTo("ai.neargo.shop.trade.service.impl.ServiceExecutionServiceImpl");
        var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        createEmployeeAccount();
        var person = asMerchant(() -> personnel.createPersonnel(entity, store, actor,
                new CreatePersonnelReq("execution technician", null, new AssignPersonnelReq(0L, null, store,
                        start.minusDays(2), start.plusDays(40), true, null, 0, List.of("TECHNICIAN"),
                        List.of(new AssignPersonnelReq.Skill(goods, sku, null)))), "person-" + sku));
        asMerchant(() -> personnel.linkPersonnelAccount(entity, store, actor, person.personnelNo(),
                new LinkPersonnelAccountReq(person.revision(), employeeAccount), "link-" + sku));
        long resourceRevision = jdbc.queryForObject("SELECT version FROM mch_resource WHERE resource_no=?",
                Long.class, person.resourceNo());
        asMerchant(() -> schedules.saveScheduleRule(entity, store, actor, new SaveScheduleRuleReq(null,
                person.resourceNo(), resourceRevision, "UTC", 127, 0, 1440, 0, start.toLocalDate().minusDays(1),
                start.toLocalDate().plusDays(30), "ACTIVE", "execution fixture real schedule"), "schedule-" + sku));
        var registration = guestParty ? guest("execution") : null;
        String guestNo = registration == null ? null : registration.guestNo();
        String customerRef = guestParty ? null : realConsumerReference();
        String requirement = jdbc.queryForObject("SELECT requirement_no FROM bty_service_requirement WHERE entity_no=?",
                String.class, entity);
        var assignments = List.of(dto("AppointmentAssignmentReq", 0, requirement, person.resourceNo(),
            person.personnelNo()));
        var items = List.of(dto("AppointmentItemReq", sku, quantity));
        Object preview = asMerchant(() -> call(bean("AppointmentService"), "previewAppointment", entity, store, actor,
                dto("PreviewAppointmentReq", store, customerRef, items, assignments, start, guestNo)));
        Object appointment = asMerchant(() -> call(bean("AppointmentService"), "createAppointment", entity,
            store, actor,
                dto("CreateAppointmentReq", value(preview, "previewNo"), customerRef, List.of(), null, guestNo),
                "appointment-" + sku));
        assertThat(value(appointment, "status")).isEqualTo("CONFIRMED");
        String appointmentNo = (String) value(appointment, "appointmentNo");
        Object identity = dto("ReceptionIdentityProof", customerRef, guestNo,
                registration == null ? null : registration.claimToken());
        Object arrived = asMerchant(() -> call(bean("ReceptionService"), "checkIn", entity, store, actor,
                dto("CheckInReq", appointmentNo, identity, value(appointment, "revision")), "arrival-" + sku));
        assertThat(value(arrived, "status")).isEqualTo("CHECKED_IN");
        long appointmentRevision = jdbc.queryForObject("SELECT version FROM bty_appointment WHERE appointment_no=?",
                Long.class, appointmentNo);
        Object opened = asMerchant(() -> call(bean("WorkOrderService"), "openWorkOrder", entity, store, actor,
                dto("OpenWorkOrderReq", customerRef, store, items, appointmentNo, guestNo, appointmentRevision,
                        assignments, start), "open-" + sku));
        String workOrder = (String) value(opened, "workOrderNo");
        var original = admittedCounter(workOrder, guestParty ? new CounterCustomerRef("GUEST", null, guestNo,
            registration.claimToken())
                : new CounterCustomerRef("USER", member, null, null), "execution", quantity);
        for (int unit = 0; unit < quantity; unit++) bind(original, "execution-" + unit);
        var task = jdbc.queryForMap(
            "SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=? ORDER BY task_no " +
                "LIMIT 1",
                workOrder);
        long scopeRevision = jdbc.queryForObject("SELECT scope_revision FROM bty_work_order WHERE work_order_no=?",
                Long.class, workOrder);
        var request = new FreezeStart(new ExecutionScope("MAIN", entity, store), workOrder, (String) task.get(
            "task_no"),
                ((Number) task.get("version")).longValue(), ((Number) task.get("binding_revision")).longValue(),
                scopeRevision, "start-" + sku);
        return new Flow(original, request, person);
    }

    private void createEmployeeAccount() {
        employeeAccount = "ET-" + sku;
        String employeeUser = "EU-" + sku;
        String role = "ER-" + sku;
        jdbc.update("INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                + " VALUES (?,'execution employee','NORMAL',NOW(),NOW())", employeeUser);
        jdbc.update("INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                + " VALUES (?,?,?,0,'ACTIVE',NOW(),NOW())", entity, employeeAccount, employeeUser);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                + " VALUES (?,?,'execution self role',?,NOW(),NOW())", entity, role,
                json.writeValueAsString(List.of("biz:service:task:self", "biz:service:task:view")));
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())", employeeAccount, store, role);
    }

    private Grant prepare(FreezeStart request) {
        Object result = prepareRaw(request);
        Grant grant = (Grant) value(result, "grant");
        assertThat(grant).as("No pending owner effect may be reported as a prepared grant").isNotNull();
        return grant;
    }

    private Object prepareRaw(FreezeStart request) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> call(bean("ServiceTaskExecutionService"), "prepareTask", request));
    }

    private long executionCount(String table) {
        if (!List.of("ord_service_execution_unit", "ord_service_execution",
            "bty_service_participation").contains(table)) {
            throw new IllegalArgumentException("fixed verification table required");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_no=?", Long.class, entity);
    }

    private Object inventoryEffects(String orderNo) {
        return new JdbcTemplate(inventoryDataSource).queryForList(
                "SELECT r.id reservation_id,r.disposition_revision,r.status,l.id,l.qty,"
                        + "l.issued_qty,l.released_qty,l.outbound_no FROM inv_reservation r"
                        + " JOIN inv_reservation_line l ON l.reservation_id=r.reservation_id WHERE " +
                            "r.external_ref=? ORDER BY l.id",
                orderNo);
    }

    private static Object value(Object record, String name) {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual owner result required", failure);
        }
    }

    private record Flow(ManualOriginal original, FreezeStart request, PersonnelVO person) {
    }
    @Test
    void unknownAcceptedIntentCannotMintTheFirstNativeClaim() {
        var lookup = prepared();
        var result = retention.continueAcceptedRetention(new InventoryExecutionStockRetentionPort.AcceptedRetention(
                lookup, "UNKNOWN_INTENT", "a".repeat(64)));
        assertThat(result.outcome()).isIn(ExecutionStockRetentionPort.Outcome.UNKNOWN,
                ExecutionStockRetentionPort.Outcome.UNAVAILABLE);
        assertThat(inv().queryForObject("SELECT COUNT(*) FROM inv_execution_retention_claim WHERE component_no=?",
                Integer.class, lookup.component().componentNo())).isZero();
    }

    @Autowired ExecutionStockRetentionPort aggregateRetention;
    @Autowired ai.neargo.shop.invbridge.mapper.ExecutionRetentionRouteMapper retentionRoutes;

    @Test
    void nativeAggregateUsesRealNativeProofAndQueryDoesNotAcceptAnIntent() {
        var component = prepared().component();
        var original = component.original();
        var unknown = aggregateRetention.queryOriginalV2(original);
        assertThat(unknown.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.UNKNOWN);
        assertThat(retentionRoutes.find(original.reference().scope().tenantNo(), unknown.routeNo())).isNull();
        var retained = current(() -> aggregateRetention.retainV2(new ExecutionStockRetentionPort.RetainExecutionStock(
                original.reference(), original.stockOriginal(), original.confirmationOperationNo(),
                original.retentionNo(), original.requestHash())));
        assertThat(retained.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(retained.canStart()).isFalse();
        assertThat(retained.components()).hasSize(1);
        assertThat(retained.components().getFirst().platform()).isNull();
        assertThat(retained.components().getFirst().inventory().proof().lines()).isNotEmpty();
        var row = retentionRoutes.find(original.reference().scope().tenantNo(), retained.routeNo());
        long epoch = row.getObservationEpoch();
        assertThat(aggregateRetention.queryOriginalV2(original).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(retentionRoutes.find(original.reference().scope().tenantNo(), retained.routeNo())
                .getObservationEpoch()).isEqualTo(epoch);
    }

    @Test
    void acceptedRouteCanContinueWithoutReplayingHumanAndWrongIntentCannotReplaceItsPins() {
        var original = prepared().component().original();
        var request = new ExecutionStockRetentionPort.RetainExecutionStock(original.reference(),
                original.stockOriginal(), original.confirmationOperationNo(), original.retentionNo(),
                original.requestHash());
        var retained = current(() -> aggregateRetention.retainV2(request));
        var row = retentionRoutes.find(original.reference().scope().tenantNo(), retained.routeNo());
        var wrong = aggregateRetention.continueAcceptedRetentionV2(
                new ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AcceptedRouteLookup(
                        row.getRouteNo(), row.getRouteHash(), row.getAcceptedIntentNo(), "f".repeat(64), original));
        assertThat(wrong.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        var continued = aggregateRetention.continueAcceptedRetentionV2(
                new ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AcceptedRouteLookup(
                        row.getRouteNo(), row.getRouteHash(), row.getAcceptedIntentNo(), row.getAcceptedIntentHash(),
                        original));
        assertThat(continued.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(continued.canStart()).isFalse();
    }

}
