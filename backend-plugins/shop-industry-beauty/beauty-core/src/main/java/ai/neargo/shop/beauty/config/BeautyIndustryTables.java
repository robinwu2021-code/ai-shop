package ai.neargo.shop.beauty.config;

import java.util.Set;

/**
 * 美业在行业库的表（ADR-068、方案 A6；TDD-美业域迁移新库 §2.3）。行业句柄下的 Mapper 只能碰这些表。
 *
 * <p>迁移期间，合并（B-3 工单上下文、B-4 服务参与）尚未完成的旧表也在这里；每完成一步，就把旧表换成合并后的表。
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
                    // 过渡：合并完成前代码仍读写的旧表
                    "bty_work_order_context", // → bty_work_order（B-3）
                    "bty_service_participation"); // → ind_assignment（B-4）
}
