package ai.neargo.shop.commercebridge;

import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.fulfillment.MallRecoveryFacade;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.service.PlatformMoneyHandoffService;
import ai.neargo.shop.spi.pay.PlatformPaymentRecoveryPort;

import org.junit.jupiter.api.Test;

import java.util.List;

class MallRecoveryJobTest {
    @Test
    void durableHandoffFailureDoesNotStarveCoreRecoveryAndBothCursorsRotate() {
        var core = mock(MallRecoveryFacade.class);
        var handoffs = mock(PlatformMoneyHandoffService.class);
        var recovery = mock(PlatformPaymentRecoveryPort.class);
        when(handoffs.pending(null, 1))
                .thenReturn(
                        List.of(new PlatformMoneyHandoffService.Handoff("P1", "PAY", "MAIN", "O")));
        when(handoffs.pending("P1", 1)).thenReturn(List.of());
        when(recovery.recover("P1")).thenThrow(new IllegalStateException("retry"));
        when(core.recover(new MallRecoveryFacade.Batch(0, 1)))
                .thenReturn(new MallRecoveryFacade.Result(7, 1, 0, false));
        when(core.recover(new MallRecoveryFacade.Batch(7, 1)))
                .thenReturn(new MallRecoveryFacade.Result(0, 0, 0, true));
        var job = new MallRecoveryJob(core, handoffs, recovery, mock(JobSupport.class), 1);
        job.run(null);
        job.run(null);
        job.run(null);
        verify(handoffs, times(2)).pending(null, 1);
        verify(handoffs).pending("P1", 1);
        verify(core, times(2)).recover(new MallRecoveryFacade.Batch(0, 1));
        verify(core).recover(new MallRecoveryFacade.Batch(7, 1));
        verify(recovery, times(2)).recover("P1");
    }

    @Test
    void offlineSweepRotatesItsOwnCursorAndCountsFailures() {
        var core = mock(MallRecoveryFacade.class);
        when(core.recover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MallRecoveryFacade.Result(0, 0, 0, true));
        var handoffs = mock(PlatformMoneyHandoffService.class);
        when(handoffs.pending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        var offline = mock(ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class);
        when(offline.recoverPending("MAIN", null, 2))
                .thenReturn(new ai.neargo.commerce.api.offline.OfflinePurchaseFacade.Sweep(2, 1, "O2"));
        when(offline.recoverPending("MAIN", "O2", 2))
                .thenReturn(new ai.neargo.commerce.api.offline.OfflinePurchaseFacade.Sweep(0, 0, null));
        var job = new MallRecoveryJob(core, handoffs, mock(PlatformPaymentRecoveryPort.class), mock(JobSupport.class), 2);
        job.setOffline(offline);
        job.run(null);
        job.run(null);
        job.run(null);
        verify(offline, times(2)).recoverPending("MAIN", null, 2);
        verify(offline).recoverPending("MAIN", "O2", 2);
    }
}
