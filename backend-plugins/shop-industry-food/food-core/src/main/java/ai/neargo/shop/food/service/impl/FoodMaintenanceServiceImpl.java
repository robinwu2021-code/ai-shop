package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import ai.neargo.shop.food.service.support.FoodStockSyncWorker;
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

    private Supplier<FoodReturnStockService> returnStock=()->null;
    public FoodMaintenanceServiceImpl withReturnStock(Supplier<FoodReturnStockService> source) {
        this.returnStock=source;return this;
    }
    private Supplier<ai.neargo.shop.food.service.CheckService> tables=()->null;
    public FoodMaintenanceServiceImpl withTables(Supplier<ai.neargo.shop.food.service.CheckService> source){tables=source;return this;}
    /** 开台到建账单之间正常只有几秒；超过这个时长仍没有账单的占台按补偿结束。 */
    static final java.time.Duration OPENING_GRACE = java.time.Duration.ofMinutes(5);
    private Supplier<FoodStockSyncWorker> stockSync=()->null;
    public FoodMaintenanceServiceImpl withStockSync(Supplier<FoodStockSyncWorker> source){stockSync=source;return this;}
    @Override
    public Report run(Task task, int limit) {
        if (task == null || limit < 1 || limit > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        return switch (task) {
            case SCHEDULED_DISPATCH -> {
                var report = require(dispatch, task).runDue(limit);
                yield new Report(report.completed(), report.pending());
            }
            case QUEUE_NOTICE -> new Report(require(notices, task).runDue(limit), 0);
            case PRODUCTION_RECOVERY -> {
                var consumed = require(production, task).recover(limit);
                var returns = returnStock.get();
                var sync = stockSync.get();
                var disposed = returns == null ? new Report(0, 0) : returns.recover(limit);
                var delivered = sync == null ? new Report(0, 0) : sync.recover(limit);
                yield new Report(consumed.processed() + disposed.processed() + delivered.processed(),
                        consumed.pending() + disposed.pending() + delivered.pending());
            }
            case RETURN_AMENDMENT_RECOVERY -> require(amendments, task).recover(limit);
            case TABLE_RECOVERY -> {
                // 开台补偿只在原 FOOD 宿主上执行（本地 CheckServiceImpl 同时持有两个域的写入）。
                if (!(require(tables, task) instanceof CheckServiceImpl local))
                    throw new IllegalStateException("FOOD owner for " + task + " is not local");
                yield new Report(local.recoverOpenings(OPENING_GRACE, limit), 0);
            }
        };
    }

    private static <T> T require(Supplier<T> owner, Task task) {
        T service = owner.get();
        if (service == null) throw new IllegalStateException("FOOD owner for " + task + " is not assembled");
        return service;
    }
}
