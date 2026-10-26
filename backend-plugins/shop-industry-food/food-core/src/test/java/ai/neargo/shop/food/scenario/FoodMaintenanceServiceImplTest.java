package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.FoodMaintenanceConfiguration;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.FoodMaintenanceService.Report;
import ai.neargo.shop.food.service.FoodMaintenanceService.Task;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import ai.neargo.shop.food.service.impl.FoodMaintenanceServiceImpl;
import ai.neargo.shop.food.service.impl.QueueNotificationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

/** C1：原 @Scheduled 方法体（runDue/recover）现在只能经平台任务 → FoodMaintenanceService 到达。 */
class FoodMaintenanceServiceImplTest {
    private final ScheduledDiningDispatchService dispatch = mock(ScheduledDiningDispatchService.class);
    private final QueueNotificationServiceImpl notices = mock(QueueNotificationServiceImpl.class);
    private final ProductionConsumptionService production = mock(ProductionConsumptionService.class);
    private final BusinessReturnApprovalService amendments = mock(BusinessReturnApprovalService.class);
    private final FoodMaintenanceServiceImpl service = new FoodMaintenanceServiceImpl(
            () -> dispatch, () -> notices, () -> production, () -> amendments);

    @Test
    void eachTaskReachesItsOriginalOwner() {
        when(dispatch.runDue(100)).thenReturn(new ScheduledDiningDispatchService.Report(4, 3, 1));
        when(notices.runDue(100)).thenReturn(5);
        when(production.recover(50)).thenReturn(2);
        when(amendments.recover(50)).thenReturn(1);

        assertThat(service.run(Task.SCHEDULED_DISPATCH, 100)).isEqualTo(new Report(3, 1));
        assertThat(service.run(Task.QUEUE_NOTICE, 100)).isEqualTo(new Report(5, 0));
        assertThat(service.run(Task.PRODUCTION_RECOVERY, 50)).isEqualTo(new Report(2, 0));
        assertThat(service.run(Task.RETURN_AMENDMENT_RECOVERY, 50)).isEqualTo(new Report(1, 0));
        verify(dispatch).runDue(100);
        verify(notices).runDue(100);
        verify(production).recover(50);
        verify(amendments).recover(50);
    }

    @Test
    void missingOwnerFailsOnlyThatTaskAndLimitsAreBounded() {
        var partial = new FoodMaintenanceServiceImpl(() -> dispatch, () -> null, () -> production, () -> amendments);
        assertThatThrownBy(() -> partial.run(Task.QUEUE_NOTICE, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.run(Task.QUEUE_NOTICE, 0)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.run(null, 10)).isInstanceOf(BizException.class);
    }

    @Test
    void localOwnerIsAutoConfiguredWithoutTheJobSwitch() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(FoodMaintenanceConfiguration.class.getName())
                .doesNotContain("ai.neargo.shop.food.config.FoodProductionRecoveryConfiguration");
    }
}
