package ai.neargo.shop.beauty.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.mapper.WorkOrderWorkbenchMapper;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.WorkOrderWorkbenchServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

class WorkOrderWorkbenchTest {
    @Test
    void unauthorizedActorNeverReadsQueueAndStoreOverrideIsRejected() {
        var access = mock(AppointmentAccess.class);
        var db = mock(AppointmentPersistence.class);
        var rows = mock(WorkOrderWorkbenchMapper.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var service = new WorkOrderWorkbenchServiceImpl(access, db, rows);
            doThrow(BizException.of(ErrorCode.FORBIDDEN))
                    .when(access)
                    .merchant("E", "S", "DENIED", false);
            assertThatThrownBy(
                            () ->
                                    service.list(
                                            "E",
                                            "S",
                                            "DENIED",
                                            new WorkOrderQueryReq(
                                                    null, null, null, null, null, null, null)))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(
                            () ->
                                    service.list(
                                            "E",
                                            "S",
                                            "A",
                                            new WorkOrderQueryReq(
                                                    "FOREIGN", null, null, null, null, 1, 20)))
                    .isInstanceOf(BizException.class);
            verifyNoInteractions(rows, db);
        }
    }

    @Test
    void inputConstraintsRejectInvalidPagesAndDemoOnlyStates() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (var query :
                    List.of(
                            new WorkOrderQueryReq(null, null, null, null, null, 0, 20),
                            new WorkOrderQueryReq(null, null, null, null, null, 1, 101),
                            new WorkOrderQueryReq(null, "TO_PAY", null, null, null, 1, 20),
                            new WorkOrderQueryReq(null, "COMPLETED", null, null, null, 1, 20)))
                assertThat(factory.getValidator().validate(query)).isNotEmpty();
        }
    }

    @Test
    void actualRootFinishedStateRemainsReadableWithoutRenamingTheDomain() {
        var query = new WorkOrderQueryReq(null, "SERVICE_FINISHED", null, null, null, 1, 20);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(query)).isEmpty();
        }
    }

    @Test
    void emptyLaterPageKeepsOwnerCountAndRequestedPage() {
        var access = mock(AppointmentAccess.class);
        var db = mock(AppointmentPersistence.class);
        var rows = mock(WorkOrderWorkbenchMapper.class);
        var query = new WorkOrderQueryReq(null, null, null, null, null, 4, 20);
        when(db.read(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(rows.count("E", "S", query, null, null)).thenReturn(51L);
        when(rows.list("E", "S", query, null, null, 20, 60)).thenReturn(List.of());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var service = new WorkOrderWorkbenchServiceImpl(access, db, rows);
            var result = service.list("E", "S", "A", query);
            assertThat(result.total()).isEqualTo(51);
            assertThat(result.records()).isEmpty();
            assertThat(result.page()).isEqualTo(4);
            assertThat(result.size()).isEqualTo(20);
        }
    }
}
