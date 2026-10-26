package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import java.util.function.Supplier;

/**
 * 原 FOOD 宿主上的后台补偿分派：只把平台任务转给已有的领域服务，自己不读写任何表。
 *
 * <p>依赖以 {@link Supplier} 取得：某个领域服务在当前装配里不存在时，只有对应任务报不可用，
 * 不影响其余三个任务，也不让整个宿主起不来。
 */
public class FoodMaintenanceServiceImpl implements FoodMaintenanceService {
    private final Supplier<ScheduledDiningDispatchService> dispatch;
    private final Supplier<QueueNotificationServiceImpl> notices;
    private final Supplier<ProductionConsumptionService> production;
    private final Supplier<BusinessReturnApprovalService> amendments;

    public FoodMaintenanceServiceImpl(Supplier<ScheduledDiningDispatchService> dispatch,
            Supplier<QueueNotificationServiceImpl> notices,
            Supplier<ProductionConsumptionService> production,
            Supplier<BusinessReturnApprovalService> amendments) {
        this.dispatch = dispatch;
        this.notices = notices;
        this.production = production;
        this.amendments = amendments;
    }

    @Override
    public Report run(Task task, int limit) {
        if (task == null || limit < 1 || limit > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        return switch (task) {
            case SCHEDULED_DISPATCH -> {
                var report = require(dispatch, task).runDue(limit);
                yield new Report(report.completed(), report.pending());
            }
            case QUEUE_NOTICE -> new Report(require(notices, task).runDue(limit), 0);
            case PRODUCTION_RECOVERY -> new Report(require(production, task).recover(limit), 0);
            case RETURN_AMENDMENT_RECOVERY -> new Report(require(amendments, task).recover(limit), 0);
        };
    }

    private static <T> T require(Supplier<T> owner, Task task) {
        T service = owner.get();
        if (service == null) throw new IllegalStateException("FOOD owner for " + task + " is not assembled");
        return service;
    }
}
