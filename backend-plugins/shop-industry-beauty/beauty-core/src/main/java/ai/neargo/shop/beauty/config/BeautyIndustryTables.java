package ai.neargo.shop.beauty.config;

import java.util.Set;

/**
 * 美业在行业库的表（ADR-068、方案 A6；TDD-美业域迁移新库 §2.3）。行业句柄下的 Mapper 只能碰这些表。
 *
 * <p>A6 的三组合并（B-2 门店策略、B-3 工单上下文、B-4 服务参与）已完成：共用表按行业码、配置类型或对象类型只碰美业的行。
 */
public final class BeautyIndustryTables {
    private BeautyIndustryTables() {}

    public static final Set<String> NAMES =
            Set.of(
                    // 美业执行：A6 留在行业库
                    "bty_work_order",
                    "bty_service_task",
                    "bty_service_step",
                    "bty_trade_binding",
                    "bty_service_submission",
                    "bty_reception_operation",
                    // A6 原拟并入，读代码后保留独立表（用户 2026-11-28 定，TDD §8-Q1）
                    "bty_guest_claim_receipt",
                    "bty_arrival_challenge",
                    "bty_source_observation",
                    "bty_source_allocation_guard",
                    "bty_service_execution_fence",
                    // 行业共用表：门店策略头与修订（B-2 已并）
                    "ind_store_policy",
                    "ind_config_revision",
                    // 行业共用表：服务参与（B-4 已并，对象类型 TASK）
                    "ind_assignment");
}
