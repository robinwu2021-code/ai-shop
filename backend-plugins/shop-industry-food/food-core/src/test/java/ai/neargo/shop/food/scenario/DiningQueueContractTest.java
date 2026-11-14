package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbQueueTicket;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.QueueTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.QueueSeatingPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.QueueServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class DiningQueueContractTest {
    private static final LocalDate DATE=LocalDate.of(2026,10,24);
    private OpenCheckReq check(String table) {
        return new OpenCheckReq(List.of(table),2,"PREPAY",null,List.of(new TableVersionReq(table,1L)),
                "EXCLUSIVE",List.of(),null);
    }
    @Test void durableIssueKeyReplaysWithoutAnotherTicketAndRejectsChangedParty() {
        var f=new Fixture(); var saved=new AtomicReference<FnbQueueTicket>();
        when(f.tickets.selectList(any())).thenAnswer(call -> saved.get()==null?List.of():List.of(saved.get()));
        doAnswer(call -> { saved.set(call.getArgument(0)); return 1; }).when(f.tickets).insert(any(FnbQueueTicket.class));
        var first=f.service.issue("E","S","A",new IssueReq(2,"CONTACT_REF"),"ORIGINAL_KEY");
        assertThat(f.service.issue("E","S","A",new IssueReq(2,"CONTACT_REF"),"ORIGINAL_KEY").ticketNo())
                .isEqualTo(first.ticketNo());
        assertThat(first.ticketNo()).startsWith("FQUE");
        assertThatThrownBy(() -> f.service.issue("E","S","A",new IssueReq(3,"CONTACT_REF"),"ORIGINAL_KEY"))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(f.tickets,times(1)).insert(any(FnbQueueTicket.class));
    }
    @Test void seatingCallsOriginalCheckOwnerInsideQueueTransactionWithoutIntermediateIntent() {
        var f=new Fixture();
        when(f.checks.openQueuedCheck(anyString(),anyString(),anyString(),any(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.row.getStatus()).isEqualTo("CALLED");
            assertThat(f.row.getSeatRequest()).isNull();
            assertThat((String)call.getArgument(4)).isEqualTo("QUEUE_SEAT:Q");
            return new CheckResultVO(f.check());
        });
        var result=f.service.seat("E","S","A","Q",new SeatQueueReq(0L,check("T")));
        assertThat(result.status()).isEqualTo("SEATED"); assertThat(result.checkNo()).isEqualTo("CHECK");
        assertThat(result.notificationStatus()).isEqualTo("OUTBOX_RECORDED");
        assertThat(f.service.seat("E","S","A","Q",new SeatQueueReq(0L,check("T"))).checkNo()).isEqualTo("CHECK");
        verify(f.checks,times(1)).openQueuedCheck(anyString(),anyString(),anyString(),any(),anyString());
        verify(f.checks,never()).openCheck(anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void ordinaryTableConflictLeavesCalledAndAllowsAnotherTableWithCurrentVersion() {
        var f=new Fixture();
        when(f.checks.openQueuedCheck(anyString(),anyString(),anyString(),any(),anyString()))
                .thenThrow(BizException.of(ErrorCode.CONFLICT)).thenReturn(new CheckResultVO(f.check()));
        assertThatThrownBy(() -> f.service.seat("E","S","A","Q",new SeatQueueReq(0L,check("T"))))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(f.row.getStatus()).isEqualTo("CALLED");
        assertThat(f.row.getCheckNo()).isNull(); assertThat(f.row.getSeatRequest()).isNull();
        assertThat(f.service.seat("E","S","A","Q",new SeatQueueReq(0L,check("OTHER"))).status()).isEqualTo("SEATED");
        verify(f.checks,times(2)).openQueuedCheck(anyString(),anyString(),anyString(),any(),
                org.mockito.ArgumentMatchers.eq("QUEUE_SEAT:Q"));
    }
    @Test void legacyUnknownRetainsFrozenRequestAndOriginalKey() {
        var f=new Fixture(); f.row.setStatus("SEATING"); f.row.setSeatActorNo("A");
        f.row.setSeatRequest(f.json.writeValueAsString(check("T")));
        f.row.setSeatRequestHash(FoodCommandFingerprint.hash(f.row.getSeatRequest()));
        when(f.checks.openCheck(anyString(),anyString(),anyString(),any(),anyString()))
                .thenThrow(new IllegalStateException("unknown original outcome"));
        assertThat(f.service.recoverSeating("E","S","RECOVERY","Q").status()).isEqualTo("SEATING");
        assertThat(f.service.recoverSeating("E","S","RECOVERY","Q").status()).isEqualTo("SEATING");
        assertThatThrownBy(() -> f.service.seat("E","S","A","Q",new SeatQueueReq(0L,check("OTHER"))))
                .isInstanceOf(BizException.class);
        verify(f.checks,times(2)).openCheck(org.mockito.ArgumentMatchers.eq("E"),org.mockito.ArgumentMatchers.eq("S"),
                org.mockito.ArgumentMatchers.eq("A"),org.mockito.ArgumentMatchers.eq(check("T")),
                org.mockito.ArgumentMatchers.eq("QUEUE_SEAT:Q"));
        verify(f.checks,never()).openQueuedCheck(anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void lostQueueLinkRecoversImmutableOpenCheckActionWithoutAnotherOpen() {
        var f=new Fixture();
        f.row.setStatus("SEATING"); f.row.setSeatActorNo("A");
        f.row.setSeatRequest(f.json.writeValueAsString(check("T")));
        f.row.setSeatRequestHash(FoodCommandFingerprint.hash(f.row.getSeatRequest()));
        var original=new FnbActionLog(); original.setObjectNo("CHECK"); original.setOperatorNo("A");
        when(f.actions.selectList(any())).thenReturn(List.of(original));
        when(f.checks.getCheck("E","S","CHECK")).thenReturn(new CheckDetailVO(f.check(),List.of(),List.of()));
        assertThat(f.service.recoverSeating("E","S","RECOVERY_ACTOR","Q").checkNo()).isEqualTo("CHECK");
        verify(f.checks,never()).openCheck(anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void cannotCancelAnUnresolvedSeatingAttempt() {
        var f=new Fixture(); f.row.setStatus("SEATING");
        assertThatThrownBy(() -> f.service.change("E","S","A","Q","CANCEL",new ChangeReq(0L,"Guest left"),"K"))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(f.row.getStatus()).isEqualTo("SEATING");
    }
    @Test void rejectsForeignStoreBeforeReadingQueue() {
        var f=new Fixture();
        when(f.stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","OTHER",null,
                "ACTIVE",true,null,null,null,null,0,0)));
        assertThatThrownBy(() -> f.service.get("E","S","A","Q")).isInstanceOf(BizException.class);
        verifyNoInteractions(f.tickets);
    }
    @Test void normalizerRejectsUnsupportedReservationLinkAndPartyChanges() {
        assertThatThrownBy(() -> QueueSeatingPolicy.normalize(check("T"),3)).isInstanceOf(BizException.class);
        var req=new OpenCheckReq(List.of("T"),2,"PREPAY","UNVERIFIED_BOOKING",List.of(new TableVersionReq("T",1L)),
                "EXCLUSIVE",List.of(),null);
        assertThatThrownBy(() -> QueueSeatingPolicy.normalize(req,2)).isInstanceOf(BizException.class);
    }
    private static class Fixture {
        final QueueTicketMapper tickets=mock(QueueTicketMapper.class);
        final StorePolicyMapper policyRows=mock(StorePolicyMapper.class);
        final ActionLogMapper actions=mock(ActionLogMapper.class);
        final StoreOperationService policies=mock(StoreOperationService.class);
        final CheckService checks=mock(CheckService.class);
        final StoreCardQueryPort stores=mock(StoreCardQueryPort.class);
        final ObjectMapper json=new ObjectMapper();
        final FnbQueueTicket row=new FnbQueueTicket();
        final QueueServiceImpl service;
        Fixture() {
            row.setEntityNo("E"); row.setStoreNo("S"); row.setTicketNo("Q"); row.setBusinessDate(DATE);
            row.setPartySize(2); row.setStatus("CALLED"); row.setVersion(0L);
            when(tickets.selectList(any())).thenReturn(List.of(row));
            when(tickets.updateById(any(FnbQueueTicket.class))).thenReturn(1);
            when(policyRows.selectList(any())).thenReturn(List.of(new FnbStorePolicy()));
            when(policies.getPolicy(anyString(),anyString(),any())).thenReturn(
                    new StoreOperationPolicyResultVO("P","Asia/Shanghai",240,"PREPAY",List.of(),0));
            when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","E",null,
                    "ACTIVE",true,null,null,null,null,0,0)));
            var idem=mock(IdempotencyService.class);
            doAnswer(call -> ((Supplier<?>)call.getArgument(5)).get()).when(idem)
                    .execute(anyString(),anyString(),anyString(),anyString(),any(),any());
            service=new QueueServiceImpl(tickets,policyRows,actions,policies,checks,stores,idem,
                    mock(OutboxPublisher.class),json,new Transactions());
        }
        CheckVO check() { return new CheckVO("CHECK","S",DATE,"OPEN","PREPAY",2,List.of("T"),0); }
    }
    private static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
