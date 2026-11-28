package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentCurrent;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceFence;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.job.AppointmentRecoveryJob;
import ai.neargo.shop.beauty.job.ReceptionRecoveryJob;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(OutputCaptureExtension.class)
class RecoveryScanTest {
    private final AppointmentCoordinator coordinator = mock(AppointmentCoordinator.class);
    private final BeautyMappers.AppointmentMapper roots = mock(BeautyMappers.AppointmentMapper.class);
    private final BeautyMappers.AppointmentOperationMapper operations = mock(BeautyMappers.AppointmentOperationMapper.class);
    private final BeautyMappers.AppointmentCurrentMapper currents = mock(BeautyMappers.AppointmentCurrentMapper.class);
    private final BeautyMappers.AppointmentResourceFenceMapper fences =
            mock(BeautyMappers.AppointmentResourceFenceMapper.class);

    @BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "recovery-test");
        TableInfoHelper.initTableInfo(assistant, BtyAppointmentOperation.class);
        TableInfoHelper.initTableInfo(assistant, BtyReceptionOperation.class);
    }

    @Test
    void appointmentJobReportsThePersistedOutcomeOfEachClaim() {
        var pending = appointment("A1", "PENDING");
        var broken = appointment("A2", "PENDING");
        var later = appointment("A3", "PENDING");
        var completed = appointment("A3", "SUCCEEDED");
        when(operations.selectList(any())).thenReturn(List.of(pending), List.of(pending, broken, later), List.of());
        when(operations.claim(anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);
        when(operations.selectOne(any())).thenReturn(pending, pending, pending, pending, broken, later, completed);
        doThrow(new IllegalStateException("owner payload")).when(coordinator).recover(broken);
        var job = new AppointmentRecoveryJob(new AppointmentRecovery(persistence(), coordinator));

        var waiting = job.run(null);
        assertThat(waiting.status()).isEqualTo(JobStatus.FAILED);
        assertThat(waiting.error()).isEqualTo("APPOINTMENT_RECOVERY_PENDING");
        assertThat(waiting.detail()).contains("attempted=1 completed=0 pending=1 failed=0");
        var mixed = job.run(null);
        assertThat(mixed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(mixed.error()).isEqualTo("APPOINTMENT_RECOVERY_FAILED");
        assertThat(mixed.detail()).contains("attempted=3 completed=1 pending=1 failed=1");
        assertThat(job.run(null).status()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    void receptionJobContinuesAfterAnOriginalFailsAndCountsOnlyPersistedTerminalProjection(CapturedOutput output) {
        var clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
        var rows = mock(BeautyMappers.ReceptionOperationMapper.class);
        var preparation = mock(AppointmentPreparation.class);
        var pending = reception("R1", clock);
        var broken = reception("R2", clock);
        var later = reception("R3", clock);
        var pendingOwner = appointment("A1", "PENDING");
        var failedOwner = appointment("A2", "PENDING");
        var rejectedOwner = appointment("A3", "REJECTED");
        // 候选在行业库取，能否恢复到预约侧核对（B-5d）
        when(rows.selectResourceCandidates(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(pending, broken, later));
        when(operations.receptionRecoverable(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(rows.claimResource(anyString(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);
        for (var row : List.of(pending, broken, later)) {
            when(rows.selectScoped("E", "S", row.getOperationNo())).thenReturn(row);
            when(rows.selectScopedForUpdate("E", "S", row.getOperationNo())).thenReturn(row);
        }
        when(preparation.linkedReception(pending)).thenReturn(pendingOwner);
        when(preparation.linkedReception(broken)).thenReturn(failedOwner);
        when(preparation.linkedReception(later)).thenReturn(rejectedOwner);
        doThrow(new IllegalStateException("owner payload")).when(coordinator).recover(failedOwner);
        var root = new BtyAppointment();
        root.setEntityNo("E");
        root.setStoreNo("S");
        root.setAppointmentNo("AP");
        when(roots.selectOne(any())).thenReturn(root);
        when(roots.selectScopedForUpdate("E", "S", "AP")).thenReturn(root);
        when(fences.selectScopedForUpdate("E", "S", "AP")).thenReturn(new BtyAppointmentResourceFence());
        when(currents.selectScopedForUpdate("E", "S", "AP")).thenReturn(new BtyAppointmentCurrent());
        var receipt = mock(ResourceBookingPort.Receipt.class);
        var binding = mock(ResourceSourceAuthorityPort.Binding.class);
        when(coordinator.verifiedReceipt(rejectedOwner, true)).thenReturn(receipt);
        when(receipt.binding()).thenReturn(binding);
        when(binding.action()).thenReturn("RESERVE");
        when(receipt.status()).thenReturn("REJECTED");
        when(rows.updateById(later)).thenReturn(1);
        var job = new ReceptionRecoveryJob(new ReceptionRecovery(persistence(), coordinator, preparation, rows, clock));

        try (var requestId = MDC.putCloseable("requestId", "recovery-request")) {
            var result = job.run(null);
            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).isEqualTo("RECEPTION_RECOVERY_FAILED");
            assertThat(result.detail()).contains("attempted=3 completed=1 pending=1 failed=1");
        }
        assertThat(later.getPhase()).isEqualTo("REJECTED");
        assertThat(output).contains("beauty.reception.recovery_projected requestId=recovery-request"
                + " operationNo=R3 appointmentNo=AP appointmentOperationNo=LINK-R3"
                + " receptionState=REJECTED appointmentOperationState=REJECTED")
                .doesNotContain("owner payload", "recovery_projected requestId=recovery-request operationNo=R1");
    }

    private AppointmentPersistence persistence() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return new AppointmentPersistence(roots, null, operations, currents, null, null, fences, null, null,
                null, null, manager);
    }

    private BtyAppointmentOperation appointment(String number, String state) {
        var row = new BtyAppointmentOperation();
        row.setEntityNo("E");
        row.setOperationNo(number);
        row.setVersion(0L);
        row.setState(state);
        return row;
    }

    private BtyReceptionOperation reception(String number, Clock clock) {
        var row = new BtyReceptionOperation();
        row.setEntityNo("E");
        row.setStoreNo("S");
        row.setAppointmentNo("AP");
        row.setOperationNo(number);
        row.setAppointmentOperationNo("LINK-" + number);
        row.setVersion(0L);
        row.setPhase("RESOURCE_PENDING");
        row.setRequestHash("hash");
        row.setFrozenPayload("{}");
        row.setLeaseUntil(LocalDateTime.now(clock).plusSeconds(30));
        return row;
    }
}
