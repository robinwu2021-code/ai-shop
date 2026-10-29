package ai.neargo.inventory.api.profile;

/**
 * 能力包（TDD §2.5 / §2.13）。通用内核所有业主都有；下面四个按业主档案开通。
 *
 * <p>新增一个能力包 = 新的状态机，要写 ADR（TDD §2.5「新业态接入判据」）。
 */
public enum Capability {
    /** 按 SN 逐件管理、在库周期、组装关系 */
    SERIAL,
    /** 交接出库与出库池（调整 / 撤回 / 签收 / 释放 / 退回） */
    DISPATCH_POOL,
    /** 下单预留、确认销售、释放、退款回补 */
    RESERVATION,
    /** 按需计划、生产扣料 */
    PRODUCTION
}
