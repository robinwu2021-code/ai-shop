package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.service.ServiceExecutionService;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.product.service.OriginalStockRestoreService;
import ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.ExecutionRetentionMapper;
import ai.neargo.shop.product.entity.PrdExecutionRetention;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.OriginalStockReturnPort;
import ai.neargo.shop.invbridge.port.OriginalStockReturnPortImpl;
import ai.neargo.shop.invbridge.port.InventoryStockPort;
import ai.neargo.shop.invbridge.OriginalMirrorRestore;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.product.policy.ExecutionStockRetentionGuard;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.impl.AfterSaleServiceImpl;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.impl.MerchantGoodsServiceImpl;
import ai.neargo.common.data.scope.DataScopeContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

/** Native real source/stock fixture; uses actual migration installation, never inserted history markers. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@TestPropertySource(properties = {
    "shop.inventory.stock-authority=PLATFORM",
        "shop.personnel.publication.archive-only=true", "shop.schedule.publication.archive-only=true",
        "shop.resource.publication.archive-only=true"
})
class ExecutionStockRetentionIT extends ManualSourceHostFixture {
    @Autowired PersonnelService personnel;

    @Autowired ScheduleService schedules;

    @Autowired ServiceExecutionPort executions;

    @Autowired ServiceExecutionService executionOwner;

    @Autowired ServiceExecutionSchemaVerifier executionSchema;

    @Autowired ExecutionStockRetentionSchemaVerifier retentionSchema;

    @Autowired ExecutionStockRetentionPort retention;

    @MockitoSpyBean ServiceExecutionEvidencePort evidence;

    @Autowired OriginalStockRestoreService restores;

    @MockitoSpyBean SkuMapper skus;

    @Autowired MerchantGoodsService catalog;

    @Autowired OriginalStockReturnPort originalReturns;

    @Autowired AfterSaleService afterSales;

    @Autowired AfterSaleMapper afterSaleRows;

    @MockitoSpyBean StockLockMapper stockRoots;

    @MockitoSpyBean ExecutionRetentionMapper retentionLinks;
    private String employeeAccount;

    @Test
    void nativeRetainGuardsFullAllocationAndPendingReleaseNeverUnpins() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        assertThat(retentionSchema.installation()).isEqualTo(
            ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED);
        var reference = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(reference)).facts();
        var request = ExecutionStockRetentionContract.request(reference, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        var before = jdbc.queryForList(
            "SELECT id,status,version,qty FROM prd_stock_lock WHERE lock_no=? ORDER BY id", facts.stockOriginal(
                ).orderNo());
        var receipt = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(request));
        assertThat(receipt.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(receipt.owners()).hasSize(1);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
            Long.class, request.retentionNo());
        assertThat(ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(
            request)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
            Long.class, request.retentionNo())).isEqualTo(count);
        assertThatThrownBy(() -> platformStock.release(facts.stockOriginal().orderNo())).isInstanceOf(
            BizException.class);
        assertThatThrownBy(() -> platformStock.confirm(facts.stockOriginal().orderNo())).isInstanceOf(
            BizException.class);
        assertThatThrownBy(() -> platformStock.confirm(facts.stockOriginal().orderNo(), 2)).isInstanceOf(
            BizException.class);
        assertThatThrownBy(() -> platformStock.restoreOriginal("claim-" + sku, facts.stockOriginal().orderNo(),
                List.of(new StockPort.SkuQty(sku, 1, store)))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> platformStock.receiveReturn("receipt-" + sku, "as-" + sku, facts.stockOriginal(
            ).orderNo(),
                facts.stockOriginal().needs().getFirst().fulfillmentRef(),
                List.of(new StockPort.ReturnLine(sku, 1, StockPort.ReturnQuality.SALEABLE)))).isInstanceOf(
                    BizException.class);
        assertThatThrownBy(() -> platformStock.receiveReturn("bad-receipt-" + sku, "as-" + sku,
            facts.stockOriginal().orderNo(),
                facts.stockOriginal().needs().getFirst().fulfillmentRef(),
                List.of(new StockPort.ReturnLine(sku, 1, StockPort.ReturnQuality.UNSALEABLE)))).isInstanceOf(
                    BizException.class);
        assertThat(receipt.owners().getFirst().rows()).hasSize(before.size());
        assertThatThrownBy(() -> platformStock.releaseAllocated(facts.stockOriginal().needs().getFirst(
            ).fulfillmentRef()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> platformStock.issue(facts.stockOriginal().needs().getFirst().fulfillmentRef()))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList(
            "SELECT id,status,version,qty FROM prd_stock_lock WHERE lock_no=? ORDER BY id", facts.stockOriginal(
                ).orderNo())).isEqualTo(before);
        assertThat(retention.queryOriginal(request.lookup()).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
        var resource = new ExecutionStockRetentionPort.ResourceSealLookup(reference,
                reference.plannedResourceCommandNo(), reference.plannedResourceCommandHash(), reference.planHash(),
                "cancel-" + sku, ExecutionEvidenceContract.hash("TEST_CANCEL", sku));
        assertThat(retention.releaseAfterNoEffect(new ExecutionStockRetentionPort.ReleaseAfterNoEffect(
            request.lookup(), resource)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(retention.queryOriginal(request.lookup()).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(grant.quantity().performedUnits()).isZero();
    }

    @Test
    void nativeRestoreBeforeRetainRejectsUnchangedConsumedRows() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var reference = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(reference)).facts();
        var original = facts.stockOriginal();
        platformStock.issue(original.needs().getFirst().fulfillmentRef());
        restores.restore("returned-" + sku, original.orderNo(), original.needs().stream()
                .map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(), n.sourceStoreNo(), n.fulfillmentRef(),
                    original.lifecycleVersion())).toList());
        var request = ExecutionStockRetentionContract.request(reference, original,
            facts.stockConfirmationOperationNo());
        assertThat(ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(
            request)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE execution_no=?",
            Long.class, reference.executionNo())).isZero();
    }

    @Test
    void nativeEntityAndStoreSettersCannotEraseExistingAllocation() {
        var items = List.of(new StockPort.SkuQty(sku, 1));
        platformStock.lock("floor-" + sku, items);
        var before = jdbc.queryForList("SELECT id,stock,locked_stock FROM prd_sku WHERE sku_no=? ORDER BY id", sku);
        assertThatThrownBy(() -> platformStock.setOnHand(sku, null, 0, "OTHER")).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList("SELECT id,stock,locked_stock FROM prd_sku WHERE sku_no=? ORDER BY id",
            sku)).isEqualTo(before);
        platformStock.release("floor-" + sku);
        jdbc.update("INSERT INTO prd_store_stock(entity_no,store_no,sku_no,stock,locked_stock,created_at," +
            "updated_at) VALUES (?,?,?,5,0,NOW(),NOW())", entity, store, sku);
        platformStock.lock("store-floor-" + sku, List.of(new StockPort.SkuQty(sku, 2, store)));
        assertThatThrownBy(() -> platformStock.setOnHand(sku, store, 1, "OTHER")).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT stock FROM prd_store_stock WHERE sku_no=? AND store_no=?",
            Integer.class, sku, store)).isEqualTo(5);
    }

    @Test
    void nativeAlreadyConsumedAcknowledgementRecordsOnceWithoutUnpinningOrSecondDebit() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        platformStock.issue(facts.stockOriginal().needs().getFirst().fulfillmentRef());
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        var held = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(request));
        assertThat(held.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var proof = held.owners().getFirst();
        String hash = ExecutionStockRetentionContract.issueObservationHash(request.lookup(), proof.rows());
        var before = jdbc.queryForList("SELECT id,stock,locked_stock FROM prd_sku WHERE sku_no=? ORDER BY id", sku);
        var ack = new ExecutionStockRetentionPort.AcknowledgeIssued(request.lookup(), hash);
        var result = retention.acknowledgeIssued(ack);
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(result.owners().getFirst().issueObservationHash()).isEqualTo(hash);
        assertThat(retention.acknowledgeIssued(ack).owners().getFirst().issueObservationHash()).isEqualTo(hash);
        assertThat(jdbc.queryForList("SELECT id,stock,locked_stock FROM prd_sku WHERE sku_no=? ORDER BY id",
            sku)).isEqualTo(before);
        assertThatThrownBy(() -> platformStock.restore("return-" + sku, facts.stockOriginal().orderNo(),
                List.of(new StockPort.SkuQty(sku, 1, store)))).isInstanceOf(BizException.class);
    }

    @Test
    void inheritedRepeatableReadStillLocksNewMatchingRowsAndRollsBackMixedSet() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(request));
        String fulfillment = facts.stockOriginal().needs().getFirst().fulfillmentRef();
        String alias = fulfillment.toLowerCase(Locale.ROOT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM prd_stock_lock WHERE fulfillment_ref=?", Integer.class,
            alias) == 0) alias = fulfillment;
        String nativeAlias = alias;
        var transaction = transaction();
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM prd_stock_lock", Long.class);
            CompletableFuture.runAsync(() -> {
                platformStock.lock("new-" + sku, List.of(new StockPort.SkuQty(sku, 1, store, fulfillment, 2)));
                platformStock.confirm("new-" + sku, 2);
            }).join();
            platformStock.issue(nativeAlias);
            return null;
        })).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM prd_stock_lock WHERE lock_no=?", String.class, "new-" + sku))
                .isEqualTo("ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT status FROM prd_stock_lock WHERE lock_no=?", String.class,
            facts.stockOriginal().orderNo()))
                .isEqualTo("ALLOCATED");
    }

    @Test
    void metadataSaveCannotOverwriteLiveCountersAndConsumedProtectionRejectsBatchRetirement() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        platformStock.issue(facts.stockOriginal().needs().getFirst().fulfillmentRef());
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        assertThat(ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, () -> retention.retain(
            request)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        var before = jdbc.queryForList(
            "SELECT id,stock,locked_stock,deleted FROM prd_sku WHERE sku_no=? ORDER BY id", sku);
        assertThatThrownBy(() -> saveSkuBatch(List.of())).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList(
            "SELECT id,stock,locked_stock,deleted FROM prd_sku WHERE sku_no=? ORDER BY id", sku)).isEqualTo(before);
        var command = new MerchantGoodsService.Sku(sku, List.of("updated metadata"), 101L, null,
                ((Number) before.getFirst().get("stock")).intValue(), null, null, null, null, null, null);
        saveSkuBatch(List.of(command));
        assertThat(jdbc.queryForObject("SELECT spec FROM prd_sku WHERE sku_no=?", String.class, sku)).isEqualTo(
            "updated metadata");
        assertThat(jdbc.queryForObject("SELECT locked_stock FROM prd_sku WHERE sku_no=?", Integer.class, sku)).isZero();
        assertThat(retention.queryOriginal(request.lookup()).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void staleMetadataCasDoesNotOverwriteAConcurrentReservationOrPresaleCounter() {
        PrdSku stale = transaction().execute(status -> DataScopeContext.executeWithoutScope((
            ) -> skus.lockSkuCurrent(sku).getFirst()));
        platformStock.lock("live-" + sku, List.of(new StockPort.SkuQty(sku, 1)));
        stale.setSpec("stale catalog");
        stale.setStock(900);
        stale.setLockedStock(0);
        stale.setSoldCount(0);
        Integer affected = transaction().execute(status ->
                DataScopeContext.executeWithoutScope(() -> skus.updateCatalogMetadata(stale)));
        assertThat(affected).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_stock FROM prd_sku WHERE sku_no=?", Integer.class,
            sku)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class, sku)).isEqualTo(100);
    }

    @Test
    void operatorWithdrawalAfterEvidenceIoPreventsFirstRetentionClaim() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        doAnswer(call -> {
            Object actual = call.callRealMethod();
            long revision = jdbc.queryForObject("SELECT version FROM mch_personnel WHERE personnel_no=?",
                Long.class, flow.person().personnelNo());
            asMerchant(() -> personnel.linkPersonnelAccount(entity, store, actor, flow.person().personnelNo(),
                    new LinkPersonnelAccountReq(revision, null), "withdraw-" + sku));
            return actual;
        }).when(evidence).queryOriginalEvidence(any());
        try {
            assertThatThrownBy(() -> ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                    () -> retention.retain(request))).isInstanceOf(BizException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE execution_no=?",
                Long.class,
                    ref.executionNo())).isZero();
        } finally {
            doCallRealMethod().when(evidence).queryOriginalEvidence(any());
        }
    }

    @Test
    void multiMarketMetadataAndPhysicalStockPhasesRemainCoherent() {
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,created_at," +
            "updated_at)"
                + " VALUES (?,?,?,'AE',101,100,0,NOW(),NOW())", sku, goods, entity);
        var command = new MerchantGoodsService.Sku(sku, List.of("market metadata"), 101,
                Map.of("AE", 105L), 90, null, null, null, null, null, null);
        saveSkuBatch(List.of(command));
        assertThat(jdbc.queryForList("SELECT stock FROM prd_sku WHERE sku_no=? ORDER BY market", Integer.class, sku))
                .containsExactly(90, 90);
        assertThat(jdbc.queryForList("SELECT spec FROM prd_sku WHERE sku_no=? ORDER BY market", String.class, sku))
                .containsExactly("market metadata", "market metadata");
        platformStock.lock("multi-" + sku, List.of(new StockPort.SkuQty(sku, 2)));
        assertThatThrownBy(() -> platformStock.setOnHand(sku, null, 1, "OTHER")).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList("SELECT stock FROM prd_sku WHERE sku_no=? ORDER BY market", Integer.class, sku))
                .containsExactly(90, 90);
    }

    @Test
    void nativePhysicalSchemaDriftIsNeverTreatedAsAbsent() {
        assertThat(retentionSchema.installation()).isEqualTo(
            ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED);
        jdbc.execute("ALTER TABLE prd_execution_retention DROP INDEX idx_esr_guard");
        try {
            assertThat(retentionSchema.installation()).isEqualTo(
                ExecutionStockRetentionSchemaVerifier.Installation.CORRUPT);
            assertThatThrownBy(() -> platformStock.lock("drift-" + sku, List.of(new StockPort.SkuQty(sku, 1))))
                    .isInstanceOf(BizException.class);
        } finally {
            jdbc.execute("ALTER TABLE prd_execution_retention ADD INDEX idx_esr_guard(tenant_no,original_row_id," +
                "state)");
        }
    }

    @Test
    void trackedOriginalRejectsSourceLessAppendBeforeAnyBalanceOrRowChanges() {
        var flow = ready(true);
        String order = flow.original().source().orderNo();
        var rows = stockRows(order);
        var balances = balances();
        assertThatThrownBy(() -> platformStock.lock(order, List.of(new StockPort.SkuQty(sku, 1))))
                .isInstanceOf(BizException.class);
        assertThat(stockRows(order)).isEqualTo(rows);
        assertThat(balances()).isEqualTo(balances);
    }

    @Test
    void genuinelyUntrackedRetailKeepsItsLegacyLockBehavior() {
        platformStock.lock("legacy-" + sku, List.of(new StockPort.SkuQty(sku, 1)));
        platformStock.lock("legacy-" + sku, List.of(new StockPort.SkuQty(sku, 1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?", Long.class,
            "legacy-" + sku)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locked_stock FROM prd_sku WHERE sku_no=?", Integer.class,
            sku)).isEqualTo(2);
    }

    @Test
    void legacyReturnOfAnotherOrderWithTheSameSkuDoesNotBypassOrBlockTheRetainedOriginal() {
        String legacyOrder = "legacy-return-" + sku;
        platformStock.lock(legacyOrder, List.of(new StockPort.SkuQty(sku, 1, store)));
        platformStock.confirm(legacyOrder);
        var flow = ready(true);
        var grant = prepare(flow.request());
        var reference = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(reference)).facts();
        var request = ExecutionStockRetentionContract.request(reference, facts.stockOriginal(),
                facts.stockConfirmationOperationNo());
        current(() -> retention.retain(request));
        int before = jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class, sku);
        var result = originalReturns.restore("legacy-return-purpose-" + sku, legacyOrder,
                List.of(new StockPort.SkuQty(sku, 1, store)));
        assertThat(result).isEqualTo(StockPort.RestoreResult.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class, sku))
                .isEqualTo(before + 1);
        assertThat(retention.queryOriginal(request.lookup()).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(claims(legacyOrder)).isEmpty();
    }

    @Test
    void aLivePresalePromiseSurvivesBothStaleAndCurrentCatalogMetadataSaves() {
        jdbc.update("UPDATE prd_sku SET stock=0,presale_quota=10,sold_count=0,cutoff_at=NULL WHERE sku_no=?", sku);
        PrdSku stale = transaction().execute(status ->
                DataScopeContext.executeWithoutScope(() -> skus.lockSkuCurrent(sku).getFirst()));
        platformStock.lock("presale-" + sku, List.of(new StockPort.SkuQty(sku, 1)));
        stale.setSpec("stale presale metadata");
        stale.setStock(900);
        stale.setSoldCount(0);
        Integer staleChanged = transaction().execute(status ->
                DataScopeContext.executeWithoutScope(() -> skus.updateCatalogMetadata(stale)));
        assertThat(staleChanged).isZero();
        PrdSku current = transaction().execute(status ->
                DataScopeContext.executeWithoutScope(() -> skus.lockSkuCurrent(sku).getFirst()));
        current.setSpec("current presale metadata");
        current.setStock(900);
        current.setSoldCount(0);
        Integer updated = transaction().execute(status ->
                DataScopeContext.executeWithoutScope(() -> skus.updateCatalogMetadata(current)));
        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM prd_sku WHERE sku_no=?", Integer.class, sku))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class, sku)).isZero();
        assertThat(jdbc.queryForObject("SELECT spec FROM prd_sku WHERE sku_no=?", String.class, sku))
                .isEqualTo("current presale metadata");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MISSING", "SUBSET", "EXTRA", "OPERATION", "MANIFEST", "STORE", "LIFECYCLE", "OWNER",
        "DELETED_ROW", "VERSION", "POOL_OWNER", "FULFILLMENT"
    })
    void checkedFrozenReturnRejectsCorruptPhysicalOriginalWithoutClaimsMarkerOrOutbox(String mismatch) {
        if ("POOL_OWNER".equals(mismatch)) {
            jdbc.update("INSERT INTO prd_store_stock(entity_no,store_no,sku_no,stock,locked_stock,created_at," +
                "updated_at)"
                    + " VALUES (?,?,?,100,0,NOW(),NOW())", entity, store, sku);
        }
        var flow = ready(true, 2);
        var frozen = frozenStock(flow.original().source().orderNo());
        platformStock.issue(flow.original().source().subOrderNo());
        corruptStock(frozen, mismatch);
        var afterSale = savedReturnCase(flow.original(), "negative-" + mismatch);
        var beforeStocks = stockRows(frozen.orderNo());
        var beforeBalances = balances();
        var beforePools = jdbc.queryForList("SELECT * FROM prd_store_stock WHERE sku_no=? ORDER BY id", sku);
        var beforeClaims = claims(frozen.orderNo());
        var beforeOutbox = returnEvents(frozen.orderNo(), afterSale.getAfterSaleNo());
        assertThatThrownBy(() -> invokeReturn(afterSale, flow.original(), 1)).isInstanceOf(BizException.class);
        assertThat(stockRows(frozen.orderNo())).isEqualTo(beforeStocks);
        assertThat(balances()).isEqualTo(beforeBalances);
        assertThat(jdbc.queryForList("SELECT * FROM prd_store_stock WHERE sku_no=? ORDER BY id",
            sku)).isEqualTo(beforePools);
        assertThat(claims(frozen.orderNo())).isEqualTo(beforeClaims);
        assertThat(jdbc.queryForObject("SELECT stock_restored FROM ord_after_sale WHERE after_sale_no=?", Integer.class,
                afterSale.getAfterSaleNo())).isZero();
        assertThat(returnEvents(frozen.orderNo(), afterSale.getAfterSaleNo())).isEqualTo(beforeOutbox);
    }

    @Test
    void checkedRestoreRejectsWrongConfirmationArgumentEvenWhenRowsAreUnchanged() {
        var flow = ready(true);
        var frozen = frozenStock(flow.original().source().orderNo());
        platformStock.issue(flow.original().source().subOrderNo());
        var before = balances();
        assertThatThrownBy(() -> restores.restore("wrong-op-" + sku, frozen, "wrong-operation",
                List.of(new StockPort.SkuQty(sku, 1, store)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(balances()).isEqualTo(before);
        assertThat(claims(frozen.orderNo())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETED", "ZERO", "NEGATIVE"
    })
    void anyPriorInvalidOrDeletedClaimPreventsAnInitialRetainedProof(String kind) {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        long id = jdbc.queryForObject("SELECT id FROM prd_stock_lock WHERE lock_no=?", Long.class,
            facts.stockOriginal().orderNo());
        int qty = "ZERO".equals(kind) ? 0 : "NEGATIVE".equals(kind) ? -1 : 1;
        jdbc.update("INSERT INTO prd_stock_restore(purpose_no,original_lock_id,qty,deleted,created_at,updated_at)"
                + " VALUES (?,?,?,?,NOW(),NOW())", "invalid-" + sku, id, qty, "DELETED".equals(kind) ? 1 : 0);
        var before = balances();
        var result = current(() -> retention.retain(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(balances()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?", Long.class,
                request.retentionNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT qty FROM prd_stock_restore WHERE purpose_no=?", Integer.class,
                "invalid-" + sku)).isEqualTo(qty);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MISSING", "SUBSET", "EXTRA", "ORPHAN_CLAIM"
    })
    void originalObservationCannotAcceptMissingSubsetExtraOrOrphanedFacts(String kind) {
        var flow = ready(true, 2);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        assertThat(current(() -> retention.retain(request)).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
        if ("ORPHAN_CLAIM".equals(kind)) {
            long id = jdbc.queryForObject("SELECT id FROM prd_stock_lock WHERE lock_no=?", Long.class,
                facts.stockOriginal().orderNo());
            jdbc.update("INSERT INTO prd_stock_restore(purpose_no,original_lock_id,qty,created_at,updated_at) " +
                "VALUES (?,?,1,NOW(),NOW())",
                    "orphan-" + sku, id);
            jdbc.update("DELETE FROM prd_stock_lock WHERE id=?", id);
        } else corruptStock(facts.stockOriginal(), kind);
        var before = balances();
        var links = jdbc.queryForList(
            "SELECT * FROM prd_execution_retention WHERE retention_no=? ORDER BY original_row_id",
                request.retentionNo());
        assertThat(retention.queryOriginal(request.lookup()).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.CONFLICT);
        assertThat(balances()).isEqualTo(before);
        assertThat(jdbc.queryForList(
            "SELECT * FROM prd_execution_retention WHERE retention_no=? ORDER BY original_row_id",
                request.retentionNo()))
                .isEqualTo(links);
        if ("ORPHAN_CLAIM".equals(kind)) assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM prd_stock_restore WHERE purpose_no=?",
                Long.class, "orphan-" + sku)).isEqualTo(1);
    }

    @Test
    void actualDuplicateInsertRollsBackWholeAttemptBeforeUnknownRereadAndNormalRetry() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        var before = balances();
        var inserts = new AtomicInteger();
        doAnswer(call -> {
            call.callRealMethod();
            inserts.incrementAndGet();
            // The second genuine INSERT hits the permanent unique key; no winning receipt is fabricated.
            return call.callRealMethod();
        }).when(retentionLinks).insertRetained(any(PrdExecutionRetention.class));
        try {
            var losing = current(() -> retention.retain(request));
            assertThat(inserts.get()).isEqualTo(1);
            assertThat(losing.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.UNKNOWN);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Long.class,
                    request.retentionNo())).isZero();
            assertThat(balances()).isEqualTo(before);
        } finally {
            doCallRealMethod().when(retentionLinks).insertRetained(any(PrdExecutionRetention.class));
        }
        assertThat(current(() -> retention.retain(request)).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?", Long.class,
                request.retentionNo())).isEqualTo(1);
        assertThat(balances()).isEqualTo(before);
    }

    @Test
    void concurrentCountTasksProtectTheSameWholeLargerAllocationWithoutAnotherDebit() throws Exception {
        var flow = ready(true, 2);
        var tasks = jdbc.queryForList(
            "SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=? ORDER BY task_no",
                flow.original().workOrder());
        assertThat(tasks).hasSize(2);
        var refs = new java.util.ArrayList<ExecutionReference>();
        for (var task : tasks) {
            long scopeRevision = jdbc.queryForObject(
                "SELECT scope_revision FROM bty_work_order WHERE work_order_no=?", Long.class,
                    flow.original().workOrder());
            var start = new FreezeStart(new ExecutionScope("MAIN", entity, store), flow.original().workOrder(),
                    (String) task.get("task_no"), ((Number) task.get("version")).longValue(),
                    ((Number) task.get("binding_revision")).longValue(), scopeRevision, "count-" + task.get("task_no"));
            var grant = prepare(start);
            refs.add(current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding()))));
        }
        var firstFacts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(refs.get(0))).facts();
        var secondFacts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(refs.get(1))).facts();
        assertThat(firstFacts.stockOriginal()).isEqualTo(secondFacts.stockOriginal());
        var one = ExecutionStockRetentionContract.request(refs.get(0), firstFacts.stockOriginal(),
            firstFacts.stockConfirmationOperationNo());
        var two = ExecutionStockRetentionContract.request(refs.get(1), secondFacts.stockOriginal(),
            secondFacts.stockConfirmationOperationNo());
        var before = balances();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start);
            return current(() -> retention.retain(one));
        });
        var c = CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start);
            return current(() -> retention.retain(two));
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        var ar = a.get(20, TimeUnit.SECONDS);
        var cr = c.get(20, TimeUnit.SECONDS);
        assertThat(ar.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(cr.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(ar.owners().getFirst().rows().getFirst().originalQuantity()).isEqualTo(2);
        assertThat(cr.owners().getFirst().rows().getFirst().originalQuantity()).isEqualTo(2);
        long firstRow = ar.owners().getFirst().rows().getFirst().originalRowId();
        long secondRow = cr.owners().getFirst().rows().getFirst().originalRowId();
        assertThat(firstRow).isEqualTo(secondRow);
        assertThat(balances()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM ord_service_execution_unit WHERE entity_no=? AND state='RESERVED'",
                Long.class, entity)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bty_service_participation WHERE entity_no=?",
            Long.class, entity)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(done_qty) FROM bty_service_task WHERE work_order_no=?",
            BigDecimal.class,
                flow.original().workOrder())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void frozenPlatformReturnUsesItsRealOwnerAfterPrimarySwitchesToRealInventoryAdapter() {
        var flow = ready(true);
        var original = frozenStock(flow.original().source().orderNo());
        platformStock.issue(flow.original().source().subOrderNo());
        InventoryStockPort switched = web.getAutowireCapableBeanFactory().createBean(InventoryStockPort.class);
        assertThat(switched.stockAuthority()).isEqualTo("INVENTORY");
        var switchedPrimary = new StaticListableBeanFactory(Map.of("stock", switched)).getBeanProvider(StockPort.class);
        var router = new OriginalStockReturnPortImpl(web.getBean(ManualAdmissionMapper.class), platformStock,
                web.getBeanProvider(OriginalMirrorRestore.class), web.getBeanProvider(
                    OriginalInventoryRestoreAdapter.class), switchedPrimary, json);
        int before = jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class, sku);
        var result = router.restore("switch-" + sku, original.orderNo(), List.of(new StockPort.SkuQty(sku, 1, store)));
        assertThat(result).isEqualTo(StockPort.RestoreResult.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT stock FROM prd_sku WHERE sku_no=?", Integer.class,
            sku)).isEqualTo(before + 1);
        assertThat(claims(original.orderNo())).hasSize(1);
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
            "SELECT COUNT(*) FROM inv_reservation WHERE external_ref=?",
                Long.class, original.orderNo())).isZero();
    }

    @Test
    void genuinelyPreV545NativeInstallationIsAbsentWithoutChangingTheSharedSchema() throws Exception {
        String isolated = "c04_absent_" + sku.replace("-", "_").toLowerCase(Locale.ROOT);
        assertThat(isolated).matches("[a-z0-9_]+");
        jdbc.execute("CREATE DATABASE `" + isolated + "` CHARACTER SET utf8mb4");
        DataSource beforeV545 = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return isolated(dataSource.getConnection(), isolated);
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                return isolated(dataSource.getConnection(user, password), isolated);
            }
        };
        try {
            var config = Flyway.configure().dataSource(beforeV545).locations("classpath:db/migration")
                    .defaultSchema(isolated).schemas(isolated).createSchemas(false).target("544");
            web.getBean("migrationDialect", FlywayConfigurationCustomizer.class).customize(config);
            var migrated = config.load();
            migrated.migrate();
            migrated.validate();
            assertThat(migrated.info().current().getVersion().getVersion()).isEqualTo("544");
            var absent = new ExecutionStockRetentionSchemaVerifier(beforeV545, "flyway_schema_history", isolated);
            assertThat(absent.installation()).isEqualTo(ExecutionStockRetentionSchemaVerifier.Installation.ABSENT);
            assertThat(new JdbcTemplate(beforeV545).queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='545'", Long.class)).isZero();
            platformStock.lock("absent-policy-" + sku, List.of(new StockPort.SkuQty(sku, 1)));
            transaction().execute(status -> {
                var actualRoots = stockRoots.lockOriginal("absent-policy-" + sku);
                new ExecutionStockRetentionGuard(retentionLinks, absent).checkLocked(actualRoots);
                return null;
            });
            assertThat(retentionSchema.installation()).isEqualTo(
                ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED);
        } finally {
            jdbc.execute("DROP DATABASE `" + isolated + "`");
        }
    }

    @Test
    void newReserveWinsBeforeConcurrentRetirementAndItsAccountingPoolSurvives() throws Exception {
        var inserted = new CountDownLatch(1);
        var permitCommit = new CountDownLatch(1);
        var atRetireScan = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            inserted.countDown();
            await(permitCommit);
            return result;
        })
                .when(stockRoots).insert(any(PrdStockLock.class));
        doAnswer(call -> {
            atRetireScan.countDown();
            return call.callRealMethod();
        }).when(stockRoots).lockSkuOriginalsCurrent(any());
        try {
            var reserve = CompletableFuture.runAsync(() -> platformStock.lock("race-" + sku, List.of(
                new StockPort.SkuQty(sku, 1))));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var retire = CompletableFuture.runAsync(() -> saveSkuBatch(List.of()));
            assertThat(atRetireScan.await(10, TimeUnit.SECONDS)).isTrue();
            permitCommit.countDown();
            reserve.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> retire.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
            assertThat(jdbc.queryForObject("SELECT deleted FROM prd_sku WHERE sku_no=?", Integer.class, sku)).isZero();
            assertThat(stockRows("race-" + sku)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT locked_stock FROM prd_sku WHERE sku_no=?", Integer.class,
                sku)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? AND event_type='SKU_RETIRED'", Long.class,
                    sku)).isZero();
        } finally {
            permitCommit.countDown();
            doCallRealMethod().when(stockRoots).insert(any(PrdStockLock.class));
            doCallRealMethod().when(stockRoots).lockSkuOriginalsCurrent(any());
        }
    }

    @Test
    void retirementWinsBeforeConcurrentNewReserveAndNoOrphanLockIsInserted() throws Exception {
        var retired = new CountDownLatch(1);
        var permitCommit = new CountDownLatch(1);
        var atReserve = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            retired.countDown();
            await(permitCommit);
            return result;
        })
                .when(skus).retireUnencumbered(anyLong(), any(String.class), any(String.class), anyLong());
        doAnswer(call -> {
            atReserve.countDown();
            return call.callRealMethod();
        }).when(stockRoots).lockOriginal("retired-race-" + sku);
        try {
            var retire = CompletableFuture.runAsync(() -> saveSkuBatch(List.of()));
            assertThat(retired.await(10, TimeUnit.SECONDS)).isTrue();
            var reserve = CompletableFuture.runAsync(() -> platformStock.lock("retired-race-" + sku, List.of(
                new StockPort.SkuQty(sku, 1))));
            assertThat(atReserve.await(10, TimeUnit.SECONDS)).isTrue();
            permitCommit.countDown();
            retire.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reserve.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(
                StockPort.ShortageException.class);
            assertThat(jdbc.queryForObject("SELECT deleted FROM prd_sku WHERE sku_no=?", Integer.class,
                sku)).isEqualTo(1);
            assertThat(stockRows("retired-race-" + sku)).isEmpty();
        } finally {
            permitCommit.countDown();
            doCallRealMethod().when(skus).retireUnencumbered(anyLong(), any(String.class), any(String.class),
                anyLong());
            doCallRealMethod().when(stockRoots).lockOriginal("retired-race-" + sku);
        }
    }

    @Test
    void oneProtectedRemovalRollsBackTheWholeCatalogBatchAndAllRetirementEvents() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var facts = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).facts();
        var request = ExecutionStockRetentionContract.request(ref, facts.stockOriginal(),
            facts.stockConfirmationOperationNo());
        current(() -> retention.retain(request));
        String unprotected = "A-" + sku;
        String kept = "B-" + sku;
        for (String code : List.of(unprotected, kept)) jdbc.update(
            "INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,created_at,updated_at)"
                + " VALUES (?,?,?,'CN',101,100,0,NOW(),NOW())", code, goods, entity);
        var before = jdbc.queryForList("SELECT * FROM prd_sku WHERE goods_no=? ORDER BY id", goods);
        var events = jdbc.queryForList("SELECT * FROM sys_outbox WHERE aggregate_id IN (?,?,?) ORDER BY id",
            sku, unprotected, kept);
        var edit = new MerchantGoodsService.Sku(kept, List.of("would change"), 151, null, 50, null, null, null,
            null, null, null);
        assertThatThrownBy(() -> saveSkuBatch(List.of(edit))).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList("SELECT * FROM prd_sku WHERE goods_no=? ORDER BY id", goods)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM sys_outbox WHERE aggregate_id IN (?,?,?) ORDER BY id", sku,
            unprotected, kept)).isEqualTo(events);
        assertThat(retention.queryOriginal(request.lookup()).outcome()).isEqualTo(
            ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    private <T> T current(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, work);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("native race gate timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static Connection isolated(Connection connection, String catalog) throws SQLException {
        try {
            connection.setCatalog(catalog);
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    @Test
    void exactCheckedRestoreReplayKeepsTheSameClaimAndDoesNotAddAnotherBalanceEffect() {
        var flow = ready(true);
        var frozen = frozenStock(flow.original().source().orderNo());
        platformStock.issue(flow.original().source().subOrderNo());
        String operation = StockDispositionContract.operationNo(frozen);
        var returned = List.of(new StockPort.SkuQty(sku, 1, store));
        restores.restore("replay-" + sku, frozen, operation, returned);
        var once = balances();
        var claim = claims(frozen.orderNo());
        restores.restore("replay-" + sku, frozen, operation, returned);
        assertThat(balances()).isEqualTo(once);
        assertThat(claims(frozen.orderNo())).isEqualTo(claim).hasSize(1);
    }

    private StockDispositionPort.Original frozenStock(String order) {
        return json.readValue(jdbc.queryForObject(
            "SELECT stock_manifest_json FROM ord_manual_service_admission WHERE order_no=?",
                String.class, order), StockDispositionPort.Original.class);
    }

    private List<Map<String, Object>> stockRows(String order) {
        return jdbc.queryForList("SELECT * FROM prd_stock_lock WHERE lock_no=? ORDER BY id", order);
    }

    private List<Map<String, Object>> balances() {
        return jdbc.queryForList(
            "SELECT id,stock,locked_stock,sold_count,deleted FROM prd_sku WHERE goods_no=? ORDER BY id", goods);
    }

    private List<Map<String, Object>> claims(String order) {
        return jdbc.queryForList(
            "SELECT r.* FROM prd_stock_restore r JOIN prd_stock_lock l ON l.id=r.original_lock_id WHERE " +
                "l.lock_no=? ORDER BY r.id", order);
    }

    private List<Map<String, Object>> returnEvents(String order, String purpose) {
        return jdbc.queryForList(
            "SELECT id,event_type,aggregate_id,payload FROM sys_outbox WHERE aggregate_id IN (?,?) ORDER BY id",
                order, purpose);
    }

    private void corruptStock(StockDispositionPort.Original original, String kind) {
        String order = original.orderNo();
        switch (kind) {
            case "MISSING" -> jdbc.update("DELETE FROM prd_stock_lock WHERE lock_no=?", order);
            case "SUBSET" -> jdbc.update("UPDATE prd_stock_lock SET qty=1 WHERE lock_no=?", order);
            case "OPERATION" -> jdbc.update(
                "UPDATE prd_stock_lock SET confirmation_operation_no='wrong' WHERE lock_no=?", order);
            case "MANIFEST" -> jdbc.update("UPDATE prd_stock_lock SET source_manifest_hash=? WHERE lock_no=?",
                "f".repeat(64), order);
            case "STORE" -> jdbc.update(
                "UPDATE prd_stock_lock SET source_store_no='other-store' WHERE lock_no=?", order);
            case "LIFECYCLE" -> jdbc.update("UPDATE prd_stock_lock SET lifecycle_version=1 WHERE lock_no=?", order);
            case "OWNER" -> jdbc.update("UPDATE prd_sku SET entity_no='other-entity' WHERE sku_no=?", sku);
            case "DELETED_ROW" -> jdbc.update("UPDATE prd_stock_lock SET deleted=1 WHERE lock_no=?", order);
            case "VERSION" -> jdbc.update("UPDATE prd_stock_lock SET version=-1 WHERE lock_no=?", order);
            case "POOL_OWNER" -> jdbc.update("UPDATE prd_store_stock SET entity_no='other-entity' WHERE sku_no=?", sku);
            case "FULFILLMENT" -> jdbc.update(
                "UPDATE prd_stock_lock SET fulfillment_ref='other-fulfillment' WHERE lock_no=?", order);
            case "EXTRA" -> {
                String other = "extra-" + sku;
                platformStock.lock(other, List.of(new StockPort.SkuQty(sku, 1, store, other, 2)));
                platformStock.confirm(other, 2);
                platformStock.issue(other);
                jdbc.update("UPDATE prd_stock_lock SET lock_no=?,source_manifest_hash=?," +
                    "confirmation_operation_no=? WHERE lock_no=?",
                        order, original.manifestHash(), StockDispositionContract.operationNo(original), other);
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private OrdAfterSale savedReturnCase(ManualOriginal original, String suffix) {
        String no = "AS-" + sku + '-' + suffix;
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status," +
            "reason,stock_restored,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,'RETURN_REFUND','REFUNDED','native frozen-original return',0,NOW(),NOW())", no,
                original.source().subOrderNo(), original.source().orderNo(), buyer, entity);
        return DataScopeContext.executeWithoutScope(() -> afterSaleRows.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, no)));
    }

    private StockPort.RestoreResult invokeReturn(OrdAfterSale afterSale, ManualOriginal original, int quantity) {
        long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=? AND sku_no=?", Long.class,
                original.source().subOrderNo(), sku);
        var plan = new RefundPlan(original.source().subOrderNo(), List.of(new RefundLine(item, quantity,
            101L * quantity)), 0);
        return transaction().execute(status -> {
            try {
                var target = AopTestUtils.getUltimateTargetObject(afterSales);
                var method = AfterSaleServiceImpl.class.getDeclaredMethod("restoreStockIfReturned",
                    OrdAfterSale.class, RefundPlan.class);
                method.setAccessible(true);
                var result = (StockPort.RestoreResult) method.invoke(target, afterSale, plan);
                if (result == StockPort.RestoreResult.COMPLETED) DataScopeContext.executeWithoutScope((
                    ) -> afterSaleRows.updateById(afterSale));
                return result;
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(web.getBean("transactionManager", PlatformTransactionManager.class));
    }

    private void saveSkuBatch(List<MerchantGoodsService.Sku> submitted) {
        // Exercise the actual catalog stock portion with its real dependencies and the same primary transaction.
        transaction().execute(status -> {
            try {
                var target = AopTestUtils.getUltimateTargetObject(catalog);
                var method = MerchantGoodsServiceImpl.class.getDeclaredMethod("saveSkus", String.class,
                    String.class, List.class, List.class);
                method.setAccessible(true);
                method.invoke(target, entity, goods, submitted, List.of());
                return null;
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
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
}
