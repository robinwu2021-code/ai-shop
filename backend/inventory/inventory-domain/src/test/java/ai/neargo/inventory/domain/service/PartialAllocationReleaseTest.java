package ai.neargo.inventory.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import ai.neargo.inventory.domain.entity.InvReservation;
import ai.neargo.inventory.domain.entity.InvReservationLine;
import ai.neargo.inventory.domain.mapper.InventoryMappers.*;
import ai.neargo.inventory.domain.service.impl.ReservationServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartialAllocationReleaseTest {
    @Test void cumulativeOriginalQuantityReleasesOnlyDeltaAndReplaysCannotDoubleRelease() {
        var heads = mock(ReservationMapper.class); var lines = mock(ReservationLineMapper.class);
        var balances = mock(BalanceMapper.class);
        var service = new ReservationServiceImpl(heads,lines,balances,mock(OutboundService.class),mock(InboundService.class));
        var head = new InvReservation(); head.setReservationId("R1"); head.setOwnerId("E1"); head.setExternalRef("O1");
        head.setLifecycleVersion(2); head.setStatus("ALLOCATED");
        var line = new InvReservationLine(); line.setReservationId("R1"); line.setOwnerId("E1");
        line.setFulfillmentRef("S1"); line.setItemId("I1"); line.setLocationId("W1");
        line.setQty(3); line.setIssuedQty(0); line.setReleasedQty(0);
        when(heads.selectOne(any())).thenReturn(head); when(lines.selectList(any())).thenReturn(List.of(line));
        when(balances.unhold("E1","I1","W1",1)).thenReturn(1);
        when(lines.updateById(line)).thenReturn(1); when(heads.updateById(head)).thenReturn(1);
        for (int target : new int[]{1,1,2,3,2}) service.releaseAllocatedTo("E1","O1","S1",
                List.of(new ReservationService.ReleaseTarget("I1",target)));
        assertThat(line.getReleasedQty()).isEqualTo(3); assertThat(head.getStatus()).isEqualTo("RELEASED");
        verify(balances,times(3)).unhold("E1","I1","W1",1);
        assertThatThrownBy(() -> service.releaseAllocatedTo("E1","O1","S1",
                List.of(new ReservationService.ReleaseTarget("I1",4)))).isInstanceOf(RuntimeException.class);
        verify(balances,times(3)).unhold(anyString(),anyString(),anyString(),anyInt());
    }
}
