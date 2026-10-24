package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import java.util.Map;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.entity.FnbReservation;
import ai.neargo.shop.food.entity.FnbReservationTable;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.ReservationServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/** Lifecycle policy tests use the actual lock/validation service; database race tests remain separate. */
class ReservationLifecycleTest {
    final ReservationMapper rows=mock(ReservationMapper.class);
    final ReservationTableMapper memberships=mock(ReservationTableMapper.class);
    final TableAreaMapper areas=mock(TableAreaMapper.class);
    final TableMapper tables=mock(TableMapper.class);
    final TableOccupancyMapper occupancies=mock(TableOccupancyMapper.class);
    final ActionLogMapper actions=mock(ActionLogMapper.class);
    final CheckService checks=mock(CheckService.class);
    final StoreOperationService policies=mock(StoreOperationService.class);
    final IdempotencyService idem=mock(IdempotencyService.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final AtomicReference<FnbReservation> saved=new AtomicReference<>();
    final ReservationServiceImpl service;
    final StoreCardQueryPort stores=mock(StoreCardQueryPort.class);
    final OutboxPublisher outbox=mock(OutboxPublisher.class);

    ReservationLifecycleTest() {
        var card=mock(StoreCard.class); when(card.entityNo()).thenReturn("E"); when(card.active()).thenReturn(true);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S",card));
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(idem.execute(anyString(),anyString(),anyString(),anyString(),eq(DetailVO.class),any()))
                .thenAnswer(call -> ((Supplier<?>)call.getArgument(5)).get());
        when(rows.selectList(any())).thenAnswer(call -> saved.get()==null?List.of():List.of(saved.get()));
        doAnswer(call -> { saved.set(call.getArgument(0)); return 1; }).when(rows).insert(any(FnbReservation.class));
        when(rows.updateById(any(FnbReservation.class))).thenAnswer(call -> {
            FnbReservation row=call.getArgument(0); row.setVersion(row.getVersion()+1); return 1;
        });
        var area=new FnbTableArea(); area.setAreaNo("A"); area.setStatus("ACTIVE");
        when(areas.selectList(any())).thenReturn(List.of(area));
        var table=new FnbTable(); table.setTableNo("T"); table.setAreaNo("A"); table.setStatus("ACTIVE");
        table.setCapacity(4); table.setVersion(3L); table.setTurnoverState("IDLE");
        when(tables.selectList(any())).thenReturn(List.of(table));
        when(occupancies.selectCount(any())).thenReturn(0L);
        when(memberships.selectList(any())).thenAnswer(call -> {
            if(saved.get()==null) return List.of();
            var row=new FnbReservationTable(); row.setTableNo("T"); return List.of(row);
        });
        service=new ReservationServiceImpl(rows,memberships,areas,tables,occupancies,actions,checks,policies,idem,
                new ObjectMapper(),stores,outbox,manager);
    }
    CreateReq request() {
        Instant start=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        return new CreateReq(2,start,start.plusSeconds(3600),"encrypted-contact-ref",List.of(new TableVersionReq("T",3L)));
    }
    void create() { service.create("E","S","staff",request(),"create-key"); }
    void code(Runnable action,ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }
    @Test void realOverlapRejectsBeforeCreatingReservation() {
        when(memberships.overlaps(eq("E"),eq("S"),eq("T"),any(),any(),isNull())).thenReturn(1L);
        code(this::create,ErrorCode.CONFLICT);
        verify(rows,never()).insert(any(FnbReservation.class));
    }
    @Test void openOccupancyNeverInventsFutureDeparture() {
        when(occupancies.selectCount(any())).thenReturn(1L);
        code(this::create,ErrorCode.CONFLICT);
        verify(rows,never()).insert(any(FnbReservation.class));
    }
    @Test void changedCreateKeyPayloadRemainsRejectedAfterIdempotencyRetention() {
        var request=request(); service.create("E","S","staff",request,"create-key");
        var changed=new CreateReq(3,request.startAt(),request.endAt(),request.contactRef(),request.tables());
        code(() -> service.create("E","S","staff",changed,"create-key"),ErrorCode.CONFLICT);
    }
    @Test void staleCancelAndEarlyNoShowDoNotReleaseAllocation() {
        create(); String number=saved.get().getReservationNo();
        code(() -> service.cancel("E","S","staff",number,new TransitionReq(9L,"cancel"),"cancel"),ErrorCode.CONFLICT);
        code(() -> service.noShow("E","S","staff",number,new TransitionReq(0L,"missing"),"missing"),ErrorCode.CONFLICT);
        assertThat(saved.get().getStatus()).isEqualTo("CONFIRMED");
    }
    @Test void cancellationEndsLocalHoldThroughOwnerStateAndEmitsAuditAndOutbox() {
        create(); var row=saved.get();
        var result=service.cancel("E","S","staff",row.getReservationNo(),new TransitionReq(0L,"guest cancelled"),"cancel");
        assertThat(result.reservation().status()).isEqualTo("CANCELLED");
        assertThat(result.reservation().version()).isEqualTo(1L);
        verify(outbox,times(2)).publish(any(),any());
    }
    @Test void paidOrExternallyHeldLegacyReservationsCannotBeMutated() {
        create(); saved.get().setResourceHoldNo("shared-authority");
        code(() -> service.cancel("E","S","staff",saved.get().getReservationNo(),
                new TransitionReq(0L,"cancel"),"cancel"),ErrorCode.FORBIDDEN);
    }
    @Test void arrivalCarriesAuthoritativePostpayPolicyAndOriginalBoundToCheckOwner() {
        create(); var row=saved.get();
        var at=LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
        row.setStartAt(at.minusMinutes(10)); row.setEndAt(at.plusMinutes(30));
        when(policies.getPolicy(eq("E"),eq("S"),any()))
                .thenReturn(new StoreOperationPolicyResultVO("POL","UTC",0,"POSTPAY",List.of(),0));
        when(checks.openReservedCheck(eq("E"),eq("S"),eq("staff"),any(),anyString(),any())).thenAnswer(call -> {
            OpenCheckReq request=call.getArgument(3);
            assertThat(request.payMode()).isEqualTo("POSTPAY");
            assertThat((Instant)call.getArgument(5)).isEqualTo(row.getEndAt().toInstant(ZoneOffset.UTC));
            assertThat(row.getStatus()).isEqualTo("ARRIVED");
            return new CheckResultVO(new CheckVO("CHECK","S",at.toLocalDate(),"OPEN","POSTPAY",2,List.of("T"),0));
        });
        var result=service.arrive("E","S","staff",row.getReservationNo(),
                new ArriveReq(0L,List.of(new TableVersionReq("T",3L))),"arrive");
        assertThat(result.reservation().checkNo()).isEqualTo("CHECK");
        verify(memberships).overlaps("E","S","T",row.getStartAt(),row.getEndAt(),row.getReservationNo());
    }
}
