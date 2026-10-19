package ai.neargo.shop.logistics.push;

import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.entity.LgsKd100SubAttempt;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.registration.Kd100AttemptStore;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushCallbackIdentityTest {
    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final WaybillProgress progress = mock(WaybillProgress.class);
    private final Kd100AttemptStore attempts = mock(Kd100AttemptStore.class);
    private final PushIngestion ingestion = new PushIngestion(waybills, mock(ChannelRouter.class), progress);

    @Test
    void oldCallbackCannotAdvanceReplacedWaybill() {
        ingestion.setKd100Attempts(attempts);
        LgsKd100SubAttempt old = new LgsKd100SubAttempt();
        old.setShipmentNo("LGS-1");
        old.setIdentityKey("SHIP-1:1");
        old.setWaybillNo("STO123");
        when(attempts.byCallbackId("old-callback")).thenReturn(Optional.of(old));
        LgsWaybill current = new LgsWaybill();
        current.setShipmentNo("LGS-1");
        current.setSourceShipmentNo("SHIP-1");
        current.setSourceShipmentVersion(2L);
        current.setWaybillNo("STO123");
        current.setCarrier("STO");
        when(waybills.selectOne(any())).thenReturn(current);

        var result = ingestion.ingest("kuaidi100",
                new PushReceiver.Parsed(true, "shentong", "STO123", null, false, null, "old-callback"), "{}");

        assertThat(result).isEqualTo(PushIngestion.Outcome.UNKNOWN_WAYBILL);
        verify(progress, never()).apply(any(), any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void bareCallbackCannotClaimModernShipment() {
        LgsWaybill current = new LgsWaybill();
        current.setSourceShipmentNo("SHIP-2");
        current.setSourceShipmentVersion(1L);
        current.setWaybillNo("STO456");
        current.setCarrier("STO");
        when(waybills.selectOne(any())).thenReturn(current);

        var result = ingestion.ingest("kuaidi100",
                new PushReceiver.Parsed(true, "shentong", "STO456", null, false, null), "{}");

        assertThat(result).isEqualTo(PushIngestion.Outcome.UNKNOWN_WAYBILL);
    }
}
