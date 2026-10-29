package ai.neargo.shop.beauty.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

/**
 * Existing operations GET and POST, rather than an unreachable helper, dispatch the authentic START
 * family.
 */
class ServiceOperationPreparationTest {
    private final AppointmentPersistence db = mock(AppointmentPersistence.class);
    private final AppointmentAccess access = mock(AppointmentAccess.class);
    private final BeautyMappers.ReceptionOperationMapper receptions =
            mock(BeautyMappers.ReceptionOperationMapper.class);
    private final BeautyMappers.ServiceSubmissionMapper submissions =
            mock(BeautyMappers.ServiceSubmissionMapper.class);
    private final ServiceTaskStockPreparationCoordinator preparations =
            mock(ServiceTaskStockPreparationCoordinator.class);

    @SuppressWarnings("unchecked")
    private void setup() {
        when(db.read(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var source = new BtyServiceSubmission();
        source.setAction("START");
        source.setSubmissionNo("submission");
        when(submissions.selectSubmission("E", "S", "submission")).thenReturn(source);
    }

    @Test
    void getDispatchesOnlyPersistedProgressAndNeverAuthenticatedResume() {
        setup();
        var service =
                new ServiceOperationQueryServiceImpl(
                        db,
                        access,
                        receptions,
                        submissions,
                        mock(ServiceSubmissionQuery.class),
                        mock(ServiceSubmissionAccess.class));
        service.setTaskPreparation(preparations);
        when(preparations.projectOperation("E", "S", "actor", "submission")).thenReturn(progress());
        var result = service.getOperation("E", "S", "actor", "submission");
        assertEquals("START", result.action());
        assertEquals("STOCK_RETENTION_PENDING", result.state());
        verify(preparations, never())
                .resumeAcceptedOriginal(anyString(), anyString(), anyString(), anyString());
        verify(receptions, never()).selectScoped(anyString(), anyString(), anyString());
        verify(db, never()).operation(anyString());
        verify(access, never())
                .merchant(
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void recoveryDispatchesSameOriginalHumanResumeAndNeverAppointmentFallback() {
        setup();
        var service =
                new ServiceOperationRecoveryServiceImpl(
                        db, access, null, receptions, null, null, null, null, submissions, null);
        service.setTaskPreparation(preparations);
        when(preparations.resumeAcceptedOriginal("E", "S", "actor", "submission"))
                .thenReturn(progress());
        assertEquals("START", service.recoverOperation("E", "S", "actor", "submission").action());
        verify(preparations).resumeAcceptedOriginal("E", "S", "actor", "submission");
        verify(preparations, never())
                .projectOperation(anyString(), anyString(), anyString(), anyString());
        verify(receptions, never()).selectScoped(anyString(), anyString(), anyString());
        verify(db, never()).operation(anyString());
        verify(access, never())
                .merchant(
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    private ServiceTaskPreparationVO progress() {
        return new ServiceTaskPreparationVO(
                "submission",
                "work",
                "task",
                "execution",
                "STOCK_RETENTION_PENDING",
                "PREPARED",
                "UNKNOWN",
                "AUTHENTICATED_ORIGINAL_RECOVERY",
                "AUTHENTICATED_ORIGINAL_RECOVERY",
                null);
    }
}
