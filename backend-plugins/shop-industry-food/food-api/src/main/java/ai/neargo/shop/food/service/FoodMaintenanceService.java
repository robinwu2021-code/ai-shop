package ai.neargo.shop.food.service;

/**
 * FOOD 后台补偿任务的唯一入口：由平台任务框架（外部调度器经 {@code /internal/job/{name}/run}）触发。
 *
 * <p>嵌入部署时由 food-core 本地执行；独立部署时 shop-app 只有 food-web，经内部令牌调 food-server 执行。
 * 每个任务只推进库里已有的原记录（原批次、原通知、原耗用事件、原退菜改单），不创建新的订单、收款或退款。
 */
public interface FoodMaintenanceService {

    enum Task {
        /** 预约出餐到点下厨（原批次）。 */
        SCHEDULED_DISPATCH,
        /** 排队叫号通知重发（原通知）。 */
        QUEUE_NOTICE,
        /** 出品、冲回、退菜库存处置和库存同步恢复（各自沿用原记录）。 */
        PRODUCTION_RECOVERY,
        /** 退菜改单恢复（原改单意图）。 */
        RETURN_AMENDMENT_RECOVERY,
        /** 开台补偿：先占了桌、账单没建成的占台，超时后结束（ADR-068 修订二，订单域为准）。 */
        TABLE_RECOVERY
    }

    /**
     * @param processed 本轮推进的条数：预约出餐=完成数；叫号通知=尝试重发数；耗用/改单恢复=到达终态数
     * @param pending   本轮选中但尚未确认完成的条数，包含恢复中捕获的单条异常
     */
    record Report(int processed, int pending) {
        public Report {
            if (processed < 0 || pending < 0) throw new IllegalArgumentException("Report counts must be >= 0");
        }
    }

    /** Each production recovery source uses the same per-source limit. */
    Report run(Task task, int limit);
}
