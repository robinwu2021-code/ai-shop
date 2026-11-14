package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.*;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.*;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.*;
import ai.neargo.shop.food.dto.ProductionContracts.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.mapper.FoodReturnAmendmentMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.impl.CheckCollaborationServiceImpl;
import ai.neargo.shop.food.service.impl.BusinessReturnApprovalServiceImpl;
import ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.*;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Service acceptance: collaboration uses real JDBC/transactions; other owners are explicit doubles. */
public class OrderOperationsAcceptanceTest {
    private static final String HASH = "a".repeat(64);

    public static Timestamp utc(Integer precision) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 0, 0));
    }

    @Test
    void batchMoveChangesOnlyCurrentMembershipArchivesBothCartsAndReplaysOriginalReceipt() {
        var f = new Collaboration();
        var req = new MoveReq(new CheckVersion("C1", 0), new CheckVersion("C2", 0), List.of("B1"), "handoff");
        var result = f.service.moveBatches("E", "S", "A", req, "move");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.sourceVersion()).isEqualTo(1);
        assertThat(result.targetVersion()).isEqualTo(1);
        assertThat(f.text("SELECT check_no FROM fnb_check_batch_membership WHERE detached_at IS NULL")).isEqualTo("C2");
        assertThat(f.text("SELECT check_no FROM fnb_order_batch WHERE batch_no='B1'")).isEqualTo("C1");
        assertThat(f.text("SELECT seat_no FROM fnb_order_line WHERE food_line_no='L1'")).isEqualTo("SEAT1");
        assertThat(f.db.queryForList("SELECT status FROM fnb_check_cart", String.class)).containsOnly("ARCHIVED");
        assertThat(f.service.moveBatches("E", "S", "A", req, "move")).isEqualTo(result);
        assertThat(f.count("fnb_check_batch_membership")).isEqualTo(1);
        assertThat(f.count("fnb_action_log")).isEqualTo(1);
        assertThat(f.count("fnb_business_command")).isEqualTo(1);
        assertThat(f.db.queryForList("SELECT version FROM fnb_check", Long.class)).containsOnly(1L);
        verify(f.guard).invalidateShares("E", "S", "C1", "A", "handoff");
        verify(f.guard).invalidateShares("E", "S", "C2", "A", "handoff");
    }

    @Test
    void batchMoveRejectsChangedPayloadActorStaleVersionAndForeignStoreWithoutNewMembership() {
        var f = new Collaboration();
        var req = new MoveReq(new CheckVersion("C1", 0), new CheckVersion("C2", 0), List.of("B1"), "handoff");
        f.service.moveBatches("E", "S", "A", req, "move");
        assertThatThrownBy(() -> f.service.moveBatches("E", "S", "B", req, "move")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.moveBatches("E", "S", "A", new MoveReq(req.source(), req.target(), req.batchNos(), "changed"), "move")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.moveBatches("E", "S", "A", req, "stale")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.moveBatches("E", "FOREIGN", "A", req, "foreign")).isInstanceOf(BizException.class);
        assertThat(f.count("fnb_check_batch_membership")).isEqualTo(1);
        assertThat(f.count("fnb_business_command")).isEqualTo(1);
    }

    @Test
    void batchMoveRollsBackEarlierBatchAndReceiptWhenLaterBatchHasActiveReturn() {
        var f = new Collaboration();
        f.db.update("INSERT INTO fnb_order_batch(entity_no,store_no,batch_no,check_no,status) VALUES ('E','S','B2','C1','ACCEPTED')");
        f.db.update("INSERT INTO fnb_order_line(entity_no,batch_no,food_line_no,qty,unit) VALUES ('E','B2','L2',1,'EA')");
        f.db.update("INSERT INTO fnb_return VALUES ('E','S','R','BUSINESS_REQUESTED',0)");
        f.db.update("INSERT INTO fnb_return_item VALUES ('E','R','L2',0)");
        assertThatThrownBy(() -> f.service.moveBatches("E", "S", "A", new MoveReq(new CheckVersion("C1",0),new CheckVersion("C2",0),List.of("B1","B2"),"handoff"),"return-block"))
                .isInstanceOf(BizException.class);
        assertThat(f.count("fnb_check_batch_membership")).isZero();
        assertThat(f.count("fnb_business_command")).isZero();
        assertThat(f.count("fnb_action_log")).isZero();
        assertThat(f.db.queryForList("SELECT version FROM fnb_check", Long.class)).containsOnly(0L);
        assertThat(f.db.queryForList("SELECT status FROM fnb_check_cart", String.class)).containsOnly("OPEN");
    }

    @Test
    void lineMoveRetainsOriginalSeatQuantityAndCoreBindingAndRejectsWholeBatchMoveAfterSplit() {
        var f = new Collaboration();
        var req = new LineMoveReq(new CheckVersion("C1",0),new CheckVersion("C2",0),List.of("L1"),"split");
        var moved = f.service.moveLines("E","S","A",req,"line");
        assertThat(moved.status()).isEqualTo("SUCCEEDED");
        assertThat(f.service.moveLines("E","S","A",req,"line")).isEqualTo(moved);
        var membership = f.db.queryForMap("SELECT * FROM fnb_check_line_membership WHERE active_line_no='L1'");
        assertThat(membership.get("CHECK_NO")).isEqualTo("C2");
        assertThat(membership.get("SOURCE_SEAT_NO")).isEqualTo("SEAT1");
        assertThat((BigDecimal)membership.get("QTY")).isEqualByComparingTo("1.25");
        assertThat(f.text("SELECT check_no FROM fnb_order_batch WHERE batch_no='B1'")).isEqualTo("C1");
        assertThat(f.text("SELECT payment_root_no FROM fnb_core_order_binding")).isEqualTo("CORE1");
        assertThat(f.count("fnb_check_line_membership")).isEqualTo(1);
        assertThatThrownBy(() -> f.service.moveBatches("E","S","A",new MoveReq(new CheckVersion("C1",1),new CheckVersion("C2",1),List.of("B1"),"whole"),"whole"))
                .isInstanceOf(BizException.class);
        assertThat(f.count("fnb_check_batch_membership")).isZero();
        assertThat(f.count("fnb_business_command")).isEqualTo(1);
        assertThatThrownBy(() -> f.service.moveLines("E","S","A",new LineMoveReq(new CheckVersion("C1",1),new CheckVersion("C2",1),List.of("L1"),"old owner"),"old-owner"))
                .isInstanceOf(BizException.class);
        var movedBack = f.service.moveLines("E","S","A",new LineMoveReq(new CheckVersion("C2",1),new CheckVersion("C1",1),List.of("L1"),"return responsibility"),"back");
        assertThat(movedBack.status()).isEqualTo("SUCCEEDED");
        assertThat(f.text("SELECT check_no FROM fnb_check_line_membership WHERE active_line_no='L1'")).isEqualTo("C1");
        assertThat(f.count("fnb_check_line_membership")).isEqualTo(2);
        assertThat(f.db.queryForObject("SELECT qty FROM fnb_order_line WHERE food_line_no='L1'",BigDecimal.class)).isEqualByComparingTo("1.25");
    }

    @Test
    void lineMoveRejectsReturnFrozenSettlementForeignLineAndDuplicateSelectionWithoutSideEffects() {
        var f = new Collaboration();
        var req = new LineMoveReq(new CheckVersion("C1",0),new CheckVersion("C2",0),List.of("L1"),"split");
        f.db.update("INSERT INTO fnb_settlement VALUES ('E','SET','COLLECTING',0)");
        f.db.update("INSERT INTO fnb_settlement_item VALUES ('E','SET','CORE1')");
        assertThatThrownBy(() -> f.service.moveLines("E","S","A",req,"settlement")).isInstanceOf(BizException.class);
        f.db.update("DELETE FROM fnb_settlement_item");
        f.db.update("INSERT INTO fnb_return VALUES ('E','S','R','BUSINESS_REQUESTED',0)");
        f.db.update("INSERT INTO fnb_return_item VALUES ('E','R','L1',0)");
        assertThatThrownBy(() -> f.service.moveLines("E","S","A",req,"returned")).isInstanceOf(BizException.class);
        f.db.update("DELETE FROM fnb_return_item");
        f.db.update("UPDATE fnb_order_batch SET store_no='FOREIGN'");
        assertThatThrownBy(() -> f.service.moveLines("E","S","A",req,"foreign")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.moveLines("E","S","A",new LineMoveReq(req.source(),req.target(),List.of("L1","L1"),"split"),"duplicate")).isInstanceOf(BizException.class);
        assertThat(f.count("fnb_check_line_membership")).isZero();
        assertThat(f.count("fnb_business_command")).isZero();
        assertThat(f.db.queryForList("SELECT status FROM fnb_check_cart", String.class)).containsOnly("OPEN");
    }

    @Test
    void initialApprovalFreezesOriginalReductionAndConfirmsOnlyAfterOriginalOwnerAmends() {
        var f = new Approval();
        f.prepareNewReturn();
        var reductions = List.of(new IndustryOrderAmendmentPort.Reduction(7L,"COMP",new BigDecimal("0.25"),"EA","VOIDED"));
        when(f.sources.get(any(),any())).thenReturn(new IndustryAmendmentSourcePort.Evidence(f.ref,"R",HASH,"HANDLED","A",reductions,null));
        when(f.owner.query(any(),any(),any())).thenReturn(new IndustryOrderAmendmentPort.Receipt(null,f.ref,"NOT_STARTED",true,"CNY",null,1000L,9,List.of(),null));
        var submitted = new AtomicReference<IndustryOrderAmendmentPort.Command>();
        when(f.owner.amend(any(),any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.stored.get().getCommandSnapshot()).isNotBlank();
            assertThat(f.original.getStatus()).isEqualTo("APPROVAL_PENDING");
            submitted.set(i.getArgument(0));
            return f.receipt();
        });
        var result = f.service.approve("E","S","A","R",f.req,"approve");
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.originalPayableChanged()).isTrue();
        assertThat(f.original.getStatus()).isEqualTo("APPROVED");
        assertThat(f.original.getApprovalRef()).isEqualTo(result.approvalNo());
        assertThat(submitted.get().expectedSubVersion()).isEqualTo(9);
        assertThat(submitted.get().sourceReturnNo()).isEqualTo("R");
        assertThat(submitted.get().lines()).containsExactlyElementsOf(reductions);
        assertThat(submitted.get().reference()).isEqualTo(f.ref);
        assertThat(f.stored.get().getSourceHash()).isEqualTo(HASH);
        assertThat(f.stored.get().getReceiptSnapshot()).contains("AMEND1");
        assertThat(f.service.approve("E","S","A","R",f.req,"approve")).isEqualTo(result);
        verify(f.owner,times(1)).amend(any(),any());
    }

    @Test
    void initialApprovalRejectsStaleReturnVersionWithoutIntentOrOwnerMutation() {
        var f = new Approval();
        f.prepareNewReturn();
        f.original.setVersion(1L);
        assertThatThrownBy(() -> f.service.approve("E","S","A","R",f.req,"approve")).isInstanceOf(BizException.class);
        assertThat(f.stored.get()).isNull();
        assertThat(f.original.getStatus()).isEqualTo("BUSINESS_REQUESTED");
        assertThat(f.original.getApprovalRef()).isNull();
        verifyNoInteractions(f.owner,f.sources);
    }

    @Test
    void approvalWaitsForKitchenThenRecoversOriginalUnknownOwnerCommandWithoutResubmitting() {
        var f = new Approval();
        f.intent.setStatus("WAIT_KITCHEN");
        f.original.setKitchenStatus("PENDING");
        var waiting = f.service.approve("E","S","A","R",f.req,"approve");
        assertThat(waiting.pending()).isTrue();
        assertThat(f.original.getStatus()).isEqualTo("APPROVAL_PENDING");
        verifyNoInteractions(f.owner);
        f.original.setKitchenStatus("HANDLED");
        when(f.owner.query(any(),any(),any())).thenThrow(new IllegalStateException("owner timeout"));
        assertThatThrownBy(() -> f.service.approve("E","S","A","R",f.req,"approve")).isInstanceOf(IllegalStateException.class);
        assertThat(f.intent.getStatus()).isEqualTo("UNKNOWN");
        String frozen = f.intent.getCommandSnapshot();
        doReturn(f.receipt()).when(f.owner).query(any(),any(),any());
        var recovered = f.service.approve("E","S","A","R",f.req,"approve");
        assertThat(recovered.status()).isEqualTo("CONFIRMED");
        assertThat(recovered.originalPayableChanged()).isTrue();
        assertThat(f.original.getStatus()).isEqualTo("APPROVED");
        assertThat(f.intent.getCommandSnapshot()).isEqualTo(frozen);
        assertThat(f.intent.getReceiptSnapshot()).contains("AMEND1");
        verify(f.owner,never()).amend(any(),any());
        verifyNoInteractions(f.sources);
    }

    @Test
    void approvalRejectsChangedRequestKeyAndActorAndConfirmedReplayDoesNotCallOwner() {
        var f = new Approval();
        f.intent.setStatus("CONFIRMED");
        f.intent.setReceiptSnapshot(f.json.writeValueAsString(f.receipt()));
        assertThat(f.service.approve("E","S","A","R",f.req,"approve").status()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> f.service.approve("E","S","A","R",new ApproveReq(0L,"changed",null),"approve")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.approve("E","S","A","R",f.req,"other")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.approve("E","S","B","R",f.req,"approve")).isInstanceOf(BizException.class);
        verifyNoInteractions(f.owner);
        assertThat(f.original.getStatus()).isEqualTo("APPROVAL_PENDING");
    }

    @Test
    void replacementLinksOnlyAcceptedBoundSameCheckBatchAndReplaysWithoutChangingOriginalDebt() {
        var f = new Approval();
        f.intent.setStatus("CONFIRMED");
        f.intent.setReceiptSnapshot(f.json.writeValueAsString(f.receipt()));
        var req = new ReplacementReq(0L,"B2","replacement");
        var result = f.service.linkReplacement("E","S","A","R",req,"replace");
        assertThat(result.replacementBatchNo()).isEqualTo("B2");
        assertThat(result.replacementOrderNo()).isEqualTo("CORE2");
        assertThat(f.service.linkReplacement("E","S","A","R",req,"replace")).isEqualTo(result);
        assertThatThrownBy(() -> f.service.linkReplacement("E","S","A","R",new ReplacementReq(0L,"B2","changed"),"replace")).isInstanceOf(BizException.class);
        assertThat(f.original.getBatchNo()).isEqualTo("B1");
        assertThat(f.original.getStatus()).isEqualTo("APPROVAL_PENDING");
        verifyNoInteractions(f.owner);
    }

    @Test
    void replacementRejectsWrongVersionUnacceptedUnboundAndDifferentCheckBatches() {
        var f = new Approval();
        f.intent.setStatus("CONFIRMED");
        assertThatThrownBy(() -> f.service.linkReplacement("E","S","A","R",new ReplacementReq(1L,"B2","replace"),"version")).isInstanceOf(BizException.class);
        f.replacement.setCheckNo("OTHER");
        assertThatThrownBy(() -> f.service.linkReplacement("E","S","A","R",new ReplacementReq(0L,"B2","replace"),"check")).isInstanceOf(BizException.class);
        f.replacement.setCheckNo("C1");
        f.replacement.setAcceptedAt(null);
        assertThatThrownBy(() -> f.service.linkReplacement("E","S","A","R",new ReplacementReq(0L,"B2","replace"),"accepted")).isInstanceOf(BizException.class);
        f.replacement.setAcceptedAt(LocalDateTime.now());
        f.binding.setPaymentRootNo(null);
        assertThatThrownBy(() -> f.service.linkReplacement("E","S","A","R",new ReplacementReq(0L,"B2","replace"),"binding")).isInstanceOf(BizException.class);
        assertThat(f.intent.getReplacementBatchNo()).isNull();
        assertThat(f.intent.getReplacementKey()).isNull();
        verifyNoInteractions(f.owner);
    }

    @Test
    void reversePersistsPendingBeforeOwnerAndRecoversUnknownUsingSameReversalIdentity() {
        var f = new Production();
        var seen = new AtomicReference<Reverse>();
        when(f.inventory.reverse(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.head.getReversalSnapshot()).isNotNull();
            seen.set(i.getArgument(0));
            throw new IllegalStateException("inventory timeout");
        });
        var req = new ReverseReq(0L,"wrong production");
        String originalRequest = f.head.getRequestSnapshot();
        assertThatThrownBy(() -> f.service.reverse("E","S","A","EVENT",req,"reverse")).isInstanceOf(IllegalStateException.class);
        assertThat(f.head.getStatus()).isEqualTo("UNKNOWN");
        assertThat(f.statusHistory).containsSubsequence("REVERSAL_PENDING","SUBMITTED","UNKNOWN");
        var reversal = seen.get();
        assertThat(reversal.original()).isEqualTo(f.command);
        assertThat(reversal.reason()).isEqualTo("wrong production");
        assertThat(reversal.reverseEventNo()).isNotBlank();
        doAnswer(i -> {
            assertThat((Reverse)i.getArgument(0)).isEqualTo(reversal);
            return f.receipt("REVERSED");
        }).when(f.inventory).reverse(any());
        var done = f.service.reverse("E","S","A","EVENT",req,"reverse");
        assertThat(done.status()).isEqualTo("REVERSED");
        assertThat(done.quantityEvidence()).isEqualTo("INVENTORY_ISSUE_REVERSED");
        assertThat(f.head.getRequestSnapshot()).isEqualTo(originalRequest);
        assertThat(f.child.getStatus()).isEqualTo("REVERSED");
        assertThat(f.child.getActualQty()).isEqualByComparingTo("2");
        assertThat(f.service.reverse("E","S","A","EVENT",req,"reverse").status()).isEqualTo("REVERSED");
        verify(f.inventory,times(2)).reverse(any());
        verify(f.inventory,never()).consume(any());
        verify(f.inventory,never()).query(any());
    }

    /** Regression: an absent reversal must never reissue the original consumption. */
    @Test
    void reverseAbsentReceiptCannotTriggerForwardConsumption() {
        var f = new Production();
        when(f.inventory.reverse(any())).thenReturn(new Receipt("EVENT",ProductionConsumptionServiceImpl.fingerprint(f.command),"ABSENT",null,List.of(),null,null));
        // A counting owner proves actual wrong-way posting, even if FOOD rejects its response later.
        var forwardPosts = new java.util.concurrent.atomic.AtomicInteger();
        when(f.inventory.consume(any())).thenAnswer(i -> { forwardPosts.incrementAndGet(); return f.receipt("CONFIRMED"); });
        try { f.service.reverse("E","S","A","EVENT",new ReverseReq(0L,"reverse"),"absent"); }
        catch (BizException rejected) { /* Rejection or pending are both safe outcomes. */ }
        assertThat(forwardPosts.get()).as("reversal must not post a new forward issue").isZero();
        assertThat(f.head.getStatus()).isIn("REVERSAL_PENDING","SUBMITTED","UNKNOWN");
    }

    /** Regression: positive-issue confirmation is not reversal confirmation. */
    @Test
    void reverseConfirmedForwardReceiptCannotMarkReversalCompleted() {
        var f = new Production();
        when(f.inventory.reverse(any())).thenReturn(f.receipt("CONFIRMED"));
        try { f.service.reverse("E","S","A","EVENT",new ReverseReq(0L,"reverse"),"confirmed"); }
        catch (BizException rejected) { /* An owner protocol conflict is also a safe outcome. */ }
        assertThat(f.head.getStatus()).as("forward CONFIRMED cannot settle a pending reversal").isIn("REVERSAL_PENDING","SUBMITTED","UNKNOWN");
        assertThat(f.head.getReversalEventNo()).isNotBlank();
        verify(f.inventory,never()).consume(any());
    }

    @Test
    void reverseRejectsStaleVersionNonconfirmedChangedReplayAndForeignDirectory() {
        var f = new Production();
        assertThatThrownBy(() -> f.service.reverse("E","S","A","EVENT",new ReverseReq(1L,"reason"),"r")).isInstanceOf(BizException.class);
        f.head.setStatus("UNKNOWN");
        assertThatThrownBy(() -> f.service.reverse("E","S","A","EVENT",new ReverseReq(0L,"reason"),"r")).isInstanceOf(BizException.class);
        f.head.setStatus("CONFIRMED");
        when(f.inventory.reverse(any())).thenReturn(f.receipt("REVERSED"));
        f.service.reverse("E","S","A","EVENT",new ReverseReq(0L,"reason"),"r");
        assertThatThrownBy(() -> f.service.reverse("E","S","A","EVENT",new ReverseReq(0L,"changed"),"r")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.reverse("E","OTHER","A","EVENT",new ReverseReq(0L,"reason"),"r")).isInstanceOf(BizException.class);
        assertThat(f.head.getStatus()).isEqualTo("REVERSED");
        verify(f.inventory,times(1)).reverse(any());
    }

    @Test
    void newAdjustmentsExpandFrozenMaterialRatioPublishOneEventAndConfirmOriginalOwnerQuantity() {
        for (String kind : List.of("MATERIAL_SUPPLEMENT","MATERIAL_WASTE")) {
            var f = new NewAdjustment();
            var req = new AdjustmentReq(0L,kind,new BigDecimal("0.5"),"half production unit");
            var result = f.service.adjust("E","S","A","TASK",req,"adjust");
            assertThat(result.status()).isEqualTo("CONFIRMED");
            assertThat(result.purpose()).isEqualTo(kind);
            assertThat(result.lines()).hasSize(1);
            assertThat(result.lines().getFirst().theoryQty()).isEqualByComparingTo("0.75");
            assertThat(result.lines().getFirst().inventoryIssuedQty()).isEqualByComparingTo("0.75");
            assertThat(result.totalCostMinor()).isNull();
            assertThat(f.created.get().getAction()).isEqualTo(kind);
            assertThat(f.created.get().getQty()).isEqualByComparingTo("0.5");
            assertThat(f.created.get().getReason()).isEqualTo("half production unit");
            assertThat(f.command.get().purpose()).isEqualTo(kind);
            assertThat(f.command.get().lines()).containsExactly(new Line("MATERIAL",new BigDecimal("0.75"),"KG",HASH));
            assertThat(f.command.get().eventNo()).isEqualTo(f.created.get().getEventNo());
            assertThat(f.service.adjust("E","S","A","TASK",req,"adjust")).isEqualTo(result);
            assertThat(f.createdEvents).hasSize(1);
            assertThat(f.publishedEvents).containsExactly(f.created.get().getEventNo());
            verify(f.inventory,times(1)).query(any());
            verify(f.inventory,times(1)).consume(any());
            assertThatThrownBy(() -> f.service.adjust("E","S","A","TASK",new AdjustmentReq(0L,kind,new BigDecimal("0.6"),"half production unit"),"adjust")).isInstanceOf(BizException.class);
            assertThat(f.createdEvents).hasSize(1);
        }
    }

    @Test
    void adjustmentRejectsWrongKindQuantityVersionAndForeignBatchWithoutCreatingEvent() {
        var f = new Production();
        for (var req : List.of(new AdjustmentReq(0L,"OTHER",BigDecimal.ONE,"reason"),new AdjustmentReq(0L,"MATERIAL_WASTE",BigDecimal.ZERO,"reason"),new AdjustmentReq(0L,"MATERIAL_WASTE",new BigDecimal("0.0000001"),"reason"),new AdjustmentReq(1L,"MATERIAL_WASTE",BigDecimal.ONE,"reason"),new AdjustmentReq(0L,"MATERIAL_WASTE",new BigDecimal("3"),"reason"))) {
            assertThatThrownBy(() -> f.service.adjust("E","S","A","TASK",req,"adjust")).isInstanceOf(BizException.class);
        }
        when(f.batches.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> f.service.adjust("E","S","A","TASK",new AdjustmentReq(0L,"MATERIAL_SUPPLEMENT",BigDecimal.ONE,"reason"),"foreign")).isInstanceOf(BizException.class);
        assertThat(f.created.get()).isNull();
        assertThat(f.head.getStatus()).isEqualTo("CONFIRMED");
        verifyNoInteractions(f.inventory);
    }

    @Test
    void adjustmentOriginalKeyReplaysExistingEventAndChangedPayloadCannotCreateAnother() {
        var f = new Production();
        var req = new AdjustmentReq(0L,"MATERIAL_SUPPLEMENT",BigDecimal.ONE,"more stock");
        var prior = new FnbServiceEvent();
        prior.setEventNo("EVENT");
        prior.setSourceRequestHash(FoodCommandFingerprint.hash("production-adjust:v1","A","TASK",f.json.writeValueAsString(req)));
        when(f.events.selectOne(any())).thenReturn(prior);
        var result = f.service.adjust("E","S","A","TASK",req,"adjust");
        assertThat(result.sourceEventNo()).isEqualTo("EVENT");
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> f.service.adjust("E","S","A","TASK",new AdjustmentReq(0L,"MATERIAL_SUPPLEMENT",new BigDecimal("2"),"more stock"),"adjust")).isInstanceOf(BizException.class);
        assertThat(f.created.get()).isNull();
        verifyNoInteractions(f.inventory);
    }

    private static final class Collaboration {
        final JdbcTemplate db;
        final CheckBusinessGuardService guard = mock(CheckBusinessGuardService.class);
        final CheckCollaborationServiceImpl service;
        Collaboration() {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:order_ops_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            db = new JdbcTemplate(ds);
            db.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'ai.neargo.shop.food.scenario.OrderOperationsAcceptanceTest.utc'");
            String common = "entity_no VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP,created_by VARCHAR(32),updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0";
            db.execute("CREATE TABLE fnb_check ("+common+",store_no VARCHAR(32),check_no VARCHAR(32))");
            db.execute("CREATE TABLE fnb_check_cart ("+common+",store_no VARCHAR(32),check_no VARCHAR(32),status VARCHAR(32))");
            db.execute("CREATE TABLE fnb_order_batch (tenant_no VARCHAR(32) DEFAULT 'MAIN',"+common+",store_no VARCHAR(32),batch_no VARCHAR(32),check_no VARCHAR(32),status VARCHAR(32))");
            db.execute("CREATE TABLE fnb_order_line ("+common+",batch_no VARCHAR(32),food_line_no VARCHAR(32),seat_no VARCHAR(32),qty DECIMAL(18,6),unit VARCHAR(16))");
            db.execute("CREATE TABLE fnb_check_batch_membership ("+common+",membership_no VARCHAR(32),check_no VARCHAR(32),batch_no VARCHAR(32),operation_no VARCHAR(32),attached_at TIMESTAMP,detached_at TIMESTAMP)");
            db.execute("CREATE TABLE fnb_check_line_membership ("+common+",store_no VARCHAR(32),membership_no VARCHAR(32),check_no VARCHAR(32),batch_no VARCHAR(32),food_line_no VARCHAR(32),active_line_no VARCHAR(32),source_seat_no VARCHAR(32),qty DECIMAL(18,6),unit VARCHAR(16),operation_no VARCHAR(32),attached_at TIMESTAMP,detached_at TIMESTAMP,UNIQUE(entity_no,active_line_no))");
            db.execute("CREATE TABLE fnb_return (entity_no VARCHAR(32),store_no VARCHAR(32),return_no VARCHAR(32),status VARCHAR(32),deleted INT)");
            db.execute("CREATE TABLE fnb_return_item (entity_no VARCHAR(32),return_no VARCHAR(32),food_line_no VARCHAR(32),deleted INT)");
            db.execute("CREATE TABLE fnb_core_order_binding (entity_no VARCHAR(32),batch_no VARCHAR(32),payment_root_no VARCHAR(32))");
            db.execute("CREATE TABLE fnb_settlement (entity_no VARCHAR(32),settlement_no VARCHAR(32),status VARCHAR(32),deleted INT)");
            db.execute("CREATE TABLE fnb_settlement_item (entity_no VARCHAR(32),settlement_no VARCHAR(32),payment_root_no VARCHAR(32))");
            db.execute("CREATE TABLE fnb_business_command (scope_hash VARCHAR(64),request_key_hash VARCHAR(64),actor_no VARCHAR(32),request_hash VARCHAR(64),result_json VARCHAR(10000),created_at TIMESTAMP,PRIMARY KEY(scope_hash,request_key_hash))");
            db.execute("CREATE TABLE fnb_action_log ("+common+",store_no VARCHAR(32),action_no VARCHAR(32),object_type VARCHAR(32),object_no VARCHAR(32),action VARCHAR(64),operator_no VARCHAR(32),reason VARCHAR(255),request_no VARCHAR(64),after_ref VARCHAR(64))");
            for (String check : List.of("C1","C2")) {
                db.update("INSERT INTO fnb_check(entity_no,store_no,check_no) VALUES ('E','S',?)",check);
                db.update("INSERT INTO fnb_check_cart(entity_no,store_no,check_no,status) VALUES ('E','S',?,'OPEN')",check);
            }
            db.update("INSERT INTO fnb_order_batch(entity_no,store_no,batch_no,check_no,status) VALUES ('E','S','B1','C1','ACCEPTED')");
            db.update("INSERT INTO fnb_order_line(entity_no,batch_no,food_line_no,seat_no,qty,unit) VALUES ('E','B1','L1','SEAT1',1.25,'EA')");
            db.update("INSERT INTO fnb_core_order_binding VALUES ('E','B1','CORE1')");
            service = new CheckCollaborationServiceImpl(new FoodBusinessOperationsMapper(ds),guard,mock(IdempotencyService.class),new ObjectMapper(),new DataSourceTransactionManager(ds));
        }
        String text(String sql) { return db.queryForObject(sql,String.class); }
        long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    }

    private static final class Approval {
        final ObjectMapper json = new ObjectMapper();
        final ApproveReq req = new ApproveReq(0L,"approve return",null);
        final FnbReturnAmendment intent = new FnbReturnAmendment();
        final AtomicReference<FnbReturnAmendment> stored = new AtomicReference<>(intent);
        final FnbReturn original = new FnbReturn();
        final FnbOrderBatch replacement = new FnbOrderBatch();
        final FnbCoreOrderBinding binding = new FnbCoreOrderBinding();
        final IndustryOrderAmendmentPort owner = mock(IndustryOrderAmendmentPort.class);
        final IndustryAmendmentSourcePort sources = mock(IndustryAmendmentSourcePort.class);
        final BusinessReturnApprovalServiceImpl service;
        final Reference ref = new Reference(new Scope("MAIN","E","S"),new PricingSourceRef("FOOD","CART","CART1",0L,null,null),"CORE1","SUB1");
        @SuppressWarnings("unchecked") Approval() {
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "order-operations-acceptance");
            assistant.setCurrentNamespace("order-operations-acceptance");
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, FnbOrderBatch.class);
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, FnbCoreOrderBinding.class);
            var intents = mock(FoodReturnAmendmentMapper.class);
            var returns = mock(ReturnMapper.class);
            var batches = mock(OrderBatchMapper.class);
            var bindings = mock(CoreOrderBindingMapper.class);
            var provider = (ObjectProvider<IndustryOrderAmendmentPort>)mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(i -> Stream.of(owner));
            intent.setEntityNo("E"); intent.setStoreNo("S"); intent.setReturnNo("R"); intent.setApprovalNo("APR"); intent.setVersion(0L); intent.setActorNo("A"); intent.setStatus("UNKNOWN");
            intent.setOperationKey(FoodCommandFingerprint.hash("return-approval-key:v1","E","S","approve"));
            intent.setRequestHash(FoodCommandFingerprint.hash("return-approval:v1","A","R",json.writeValueAsString(req)));
            intent.setRequestSnapshot(json.writeValueAsString(req));
            intent.setCommandSnapshot(json.writeValueAsString(new IndustryOrderAmendmentPort.Command(ref,"A",0,"R",HASH,List.of(),"approve return",null)));
            original.setReturnNo("R"); original.setBatchNo("B1"); original.setKitchenStatus("HANDLED"); original.setStatus("APPROVAL_PENDING");
            var old = new FnbOrderBatch(); old.setBatchNo("B1"); old.setCheckNo("C1"); old.setCartNo("CART1"); old.setCartVersion(0L);
            replacement.setBatchNo("B2"); replacement.setCheckNo("C1"); replacement.setCartNo("CART2"); replacement.setStatus("ACCEPTED"); replacement.setAcceptedAt(LocalDateTime.now());
            binding.setPaymentRootNo("CORE2"); binding.setStoreOrderNo("SUB1");
            when(intents.selectOne(any())).thenAnswer(i -> stored.get());
            when(intents.insert(any(FnbReturnAmendment.class))).thenAnswer(i -> { stored.set(i.getArgument(0)); return 1; });
            when(intents.updateById(any(FnbReturnAmendment.class))).thenReturn(1);
            when(returns.selectOne(any())).thenReturn(original);
            when(returns.updateById(any(FnbReturn.class))).thenReturn(1);
            // getSqlSegment materializes the lambda values before the map is read.
            when(batches.selectOne(any())).thenAnswer(i -> {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FnbOrderBatch> q=i.getArgument(0);
                q.getSqlSegment();
                return q.getParamNameValuePairs().containsValue("B2") ? replacement : old;
            });
            when(bindings.selectOne(any())).thenAnswer(i -> {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FnbCoreOrderBinding> q=i.getArgument(0);
                q.getSqlSegment();
                if(q.getParamNameValuePairs().containsValue("B2")) return binding;
                var originalBinding=new FnbCoreOrderBinding(); originalBinding.setPaymentRootNo("CORE1"); originalBinding.setStoreOrderNo("SUB1"); return originalBinding;
            });
            service = new BusinessReturnApprovalServiceImpl(intents,returns,batches,bindings,mock(CheckCartMapper.class),sources,provider,json,new Transactions());
        }
        void prepareNewReturn() {
            stored.set(null);
            original.setEntityNo("E"); original.setStoreNo("S"); original.setBuyerNo("BUYER"); original.setCreatedBy("A"); original.setReason("return one portion"); original.setVersion(0L); original.setFinancialStatus("NOT_REQUESTED"); original.setStatus("BUSINESS_REQUESTED");
            var ownerRead = new ai.neargo.shop.food.dto.BusinessReturnContracts.OwnerRead("2026-10-25T00:00:00Z",new ai.neargo.shop.spi.trade.IndustryOrderPort.Result("CORE1","SUB1","QUOTE","ACCEPTED","CNY",1000L,0,3,List.of()));
            var component = new ai.neargo.shop.food.dto.BusinessReturnContracts.ComponentSource("COMP","MAIN","ALLOC",7L,BigDecimal.ONE,"EA",HASH,0L,"COMP",0L);
            var item = new ai.neargo.shop.food.dto.BusinessReturnContracts.SourceItem("L1","LINE","ALLOC",7L,new BigDecimal("0.25"),BigDecimal.ONE,0L,List.of(component));
            original.setRequestSnapshot(json.writeValueAsString(new ai.neargo.shop.food.dto.BusinessReturnContracts.Frozen("BUSINESS_RETURN_V1",1,"E","S","B1",0L,"CART1",0L,"BIND",0L,"BUYER","A","return one portion",HASH,ownerRead,List.of(item))));
        }
        IndustryOrderAmendmentPort.Receipt receipt() {
            return new IndustryOrderAmendmentPort.Receipt("AMEND1",ref,"CONFIRMED",false,"CNY",-100L,900L,1,List.of(),null);
        }
    }

    private static final class Production {
        final ObjectMapper json = new ObjectMapper();
        final InventoryProductionPort inventory = mock(InventoryProductionPort.class);
        final FnbProductionConsumption head = new FnbProductionConsumption();
        final FnbProductionConsumptionLine child = new FnbProductionConsumptionLine();
        final ServiceEventMapper events = mock(ServiceEventMapper.class);
        final OrderBatchMapper batches = mock(OrderBatchMapper.class);
        final AtomicReference<FnbServiceEvent> created = new AtomicReference<>();
        final List<String> statusHistory = new ArrayList<>();
        final Command command;
        final ProductionConsumptionServiceImpl service;
        Production() {
            command = new Command("E","S","EVENT","PRODUCTION",Instant.parse("2026-10-25T00:00:00Z"),List.of(new Line("MATERIAL",new BigDecimal("2"),"EA",HASH)),new PlanProof(new InventoryReservationPort.Scope("MAIN","E","B1"),HASH,"LINE",List.of(new SourceLeaf("LEAF",new BigDecimal("2")))));
            head.setEntityNo("E"); head.setStoreNo("S"); head.setConsumptionNo("CONS"); head.setSourceEventNo("EVENT"); head.setPurpose("PRODUCTION"); head.setStatus("CONFIRMED"); head.setVersion(0L);
            head.setRequestSnapshot(json.writeValueAsString(command)); head.setRequestHash(ProductionConsumptionServiceImpl.fingerprint(command)); head.setReceiptSnapshot(json.writeValueAsString(receipt("CONFIRMED")));
            child.setLineNo(1); child.setItemRef("MATERIAL"); child.setTheoryQty(new BigDecimal("2")); child.setStatus("CONFIRMED");
            var heads = mock(ProductionConsumptionMapper.class);
            var lines = mock(ProductionConsumptionLineMapper.class);
            when(heads.selectList(any())).thenReturn(List.of(head));
            when(heads.updateById(any(FnbProductionConsumption.class))).thenAnswer(i -> { statusHistory.add(((FnbProductionConsumption)i.getArgument(0)).getStatus()); return 1; });
            when(lines.selectList(any())).thenReturn(List.of(child));
            when(lines.updateById(any(FnbProductionConsumptionLine.class))).thenReturn(1);
            when(events.insert(any(FnbServiceEvent.class))).thenAnswer(i -> { created.set(i.getArgument(0)); return 1; });
            var task = new FnbKitchenTask(); task.setEntityNo("E"); task.setTaskNo("TASK"); task.setTicketNo("TICKET"); task.setTaskRole("MAKE"); task.setVersion(0L); task.setQty(new BigDecimal("2"));
            var tasks = mock(KitchenTaskMapper.class); when(tasks.selectOne(any())).thenReturn(task);
            var ticket = new FnbKitchenTicket(); ticket.setBatchNo("B1");
            var tickets = mock(KitchenTicketMapper.class); when(tickets.selectOne(any())).thenReturn(ticket);
            var batch = new FnbOrderBatch(); batch.setStatus("ACCEPTED"); batch.setBatchNo("B1"); when(batches.selectOne(any())).thenReturn(batch);
            var stores = mock(StoreCardQueryPort.class);
            when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","E",null,"ACTIVE",true,null,null,null,null,0,0)));
            service = new ProductionConsumptionServiceImpl(heads,lines,batches,mock(OrderLineMapper.class),tickets,tasks,events,mock(ConfigRevisionMapper.class),stores,inventory,json,new Transactions());
        }
        Receipt receipt(String status) {
            return new Receipt("EVENT",ProductionConsumptionServiceImpl.fingerprint(command),status,"DOC",command.lines(),null,null,List.of(new IssuedLine("LEAF","MATERIAL",new BigDecimal("2"),"EA","DOC","LEDGER",null,null,null,"UNKNOWN")));
        }
    }

    private static final class NewAdjustment {
        final ObjectMapper json = new ObjectMapper();
        final AtomicReference<FnbServiceEvent> created = new AtomicReference<>();
        final List<FnbServiceEvent> createdEvents = new ArrayList<>();
        final List<String> publishedEvents = new ArrayList<>();
        final AtomicReference<Command> command = new AtomicReference<>();
        final InventoryProductionPort inventory = mock(InventoryProductionPort.class);
        final ProductionConsumptionServiceImpl service;
        NewAdjustment() {
            var heads = mock(ProductionConsumptionMapper.class);
            var stored = new AtomicReference<FnbProductionConsumption>();
            when(heads.selectList(any())).thenAnswer(i -> stored.get()==null ? List.of() : List.of(stored.get()));
            when(heads.insert(any(FnbProductionConsumption.class))).thenAnswer(i -> { stored.set(i.getArgument(0)); return 1; });
            when(heads.updateById(any(FnbProductionConsumption.class))).thenReturn(1);
            var children = mock(ProductionConsumptionLineMapper.class);
            var storedLines = new ArrayList<FnbProductionConsumptionLine>();
            when(children.selectList(any())).thenAnswer(i -> List.copyOf(storedLines));
            when(children.insert(any(FnbProductionConsumptionLine.class))).thenAnswer(i -> { storedLines.add(i.getArgument(0)); return 1; });
            when(children.updateById(any(FnbProductionConsumptionLine.class))).thenReturn(1);
            var events = mock(ServiceEventMapper.class);
            when(events.selectOne(any())).thenAnswer(i -> created.get());
            when(events.insert(any(FnbServiceEvent.class))).thenAnswer(i -> { created.set(i.getArgument(0)); createdEvents.add(created.get()); return 1; });
            var task = new FnbKitchenTask();
            task.setEntityNo("E"); task.setTaskNo("TASK"); task.setTicketNo("TICKET"); task.setFoodLineNo("FL"); task.setStationNo("STATION"); task.setRouteVersion(1L); task.setTaskRole("MAKE"); task.setQty(new BigDecimal("2")); task.setVersion(0L);
            var tasks = mock(KitchenTaskMapper.class);
            when(tasks.selectOne(any())).thenReturn(task);
            when(tasks.updateById(any(FnbKitchenTask.class))).thenAnswer(i -> { task.setVersion(task.getVersion()+1); return 1; });
            var ticket = new FnbKitchenTicket(); ticket.setBatchNo("B1"); ticket.setKind("MAKE");
            var tickets = mock(KitchenTicketMapper.class); when(tickets.selectOne(any())).thenReturn(ticket);
            var facts = frozenFacts();
            var scope = new InventoryReservationPort.Scope("MAIN","E","B1");
            var branches = List.of(new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch(facts.payload().units().getFirst().componentRef(),"FULFILL","S","RECIPE_MATERIAL",new BigDecimal("2"),"EA",List.of(new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf("LEAF","MATERIAL",new BigDecimal("3"),"KG",HASH)),"RECIPE_REV"));
            var unhashed = new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan(scope,"PLAN",null,branches);
            var plan = new ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan(scope,"PLAN",ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts.hash(unhashed),branches);
            var batch = new FnbOrderBatch(); batch.setEntityNo("E"); batch.setStoreNo("S"); batch.setBatchNo("B1"); batch.setStatus("ACCEPTED");
            batch.setFrozenRequest(json.writeValueAsString(Map.of("facts",facts,"kitchenRoutes",new ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes(List.of(new ai.neargo.shop.food.dto.KitchenContracts.FrozenRoute("SKU","STATION","MAKE",1,"ROUTE_REV",HASH))),"inventoryPlan",plan)));
            var batches = mock(OrderBatchMapper.class); when(batches.selectOne(any())).thenReturn(batch);
            var line = new FnbOrderLine(); line.setEntityNo("E"); line.setBatchNo("B1"); line.setFoodLineNo("FL"); line.setGoodsNo("GOODS"); line.setSkuNo("SKU"); line.setSourceLineRef("LINE"); line.setQty(new BigDecimal("2")); line.setUnit("EA");
            var lines = mock(OrderLineMapper.class); when(lines.selectOne(any())).thenReturn(line);
            var stores = mock(StoreCardQueryPort.class); when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","E",null,"ACTIVE",true,null,null,null,null,0,0)));
            when(inventory.query(any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                Command c=i.getArgument(0); return new Receipt(c.eventNo(),ProductionConsumptionServiceImpl.fingerprint(c),"ABSENT",null,List.of(),null,null);
            });
            when(inventory.consume(any())).thenAnswer(i -> {
                Command c=i.getArgument(0); command.set(c);
                return new Receipt(c.eventNo(),ProductionConsumptionServiceImpl.fingerprint(c),"CONFIRMED","DOC",c.lines(),null,null,List.of(new IssuedLine("LEAF","MATERIAL",new BigDecimal("0.75"),"KG","DOC","LEDGER",null,null,null,"UNKNOWN")));
            });
            service = new ProductionConsumptionServiceImpl(heads,children,batches,lines,tickets,tasks,events,mock(ConfigRevisionMapper.class),stores,inventory,json,new Transactions());
            var outbox = mock(ai.neargo.shop.event.outbox.OutboxPublisher.class);
            when(outbox.publish(any(),any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                publishedEvents.add(created.get().getEventNo()); return null;
            });
            service.setOutbox(outbox);
        }
        DiningPriceFacts frozenFacts() {
            var selected = new OrderLineInput("LINE","MENU","ITEM","MR","GOODS","SKU",new BigDecimal("2"),null,null,List.of(),List.of());
            var cart = new CartVO("CART","S",null,null,null,"OPEN","H5","PICKUP",null,1L,List.of(selected));
            var menu = new CatalogMenuVO("MENU","MR",1L,"Menu",0,null,List.of());
            var item = new CatalogItemVO("MENU","ITEM","CATEGORY","Category","GOODS","Dish",null,new DishVO("GOODS","SINGLE",0,null,new MeasurementPolicy("EA","COUNT",0,null),0,0),List.of(),List.of(new CatalogSkuVO("SKU",null,null,100L,"CNY","KNOWN",10,"KNOWN",true,false,null,true,false,null)));
            var catalog = new CatalogVO("S","H5","PICKUP",null,List.of(menu),List.of(item));
            var offer = new ai.neargo.shop.spi.product.DishOfferQueryPort.Offer("SKU","GOODS","E","S",100L,"CNY","KNOWN",10,"KNOWN",true);
            var units = ai.neargo.shop.food.policy.DiningPriceSourcePolicy.compose(cart,catalog,Map.of("SKU",offer),json);
            var payload = new DiningPriceFacts.Payload("E",null,cart,List.of(new DiningPriceFacts.MenuRef("MENU","MR",1,HASH,List.of())),List.of(new ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact("SKU","GOODS","E","S",0)),List.of(offer),List.of(new DiningPriceFacts.Restriction("SKU",false,null,true,List.of())),units,new DiningPriceFacts.Composition(List.of(menu),List.of(item)),"CNY",200L);
            return new DiningPriceFacts(1,payload,ai.neargo.shop.common.pricing.PricingSnapshotHash.of(payload));
        }
    }

    private static final class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
