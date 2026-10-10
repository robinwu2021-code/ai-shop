package ai.neargo.shop.spi.logistics;

/**
 * 统一的物流状态（TDD-圆通物流直连 §5）。
 *
 * <p>各承运商的原始码（圆通 GOT/ARRIVAL/SIGNED、快递100 的另一套…）在**各 provider 内**
 * 归一成这几档。端上只见这个枚举，永不见承运商原始码 —— 口径散到端上迟早分叉
 * （与收件人脱敏同一条教训）。
 */
public enum TraceStatus {
    /** 已揽收 */
    PICKED,
    /** 运输中（发出 / 到达中转） */
    IN_TRANSIT,
    /**
     * 派件中（含「投柜或驿站」，见 {@link TraceResult#atLocker()}）。2026-10-09 从运输中拆出来（TDD-物流模块 批 1）：
     * 买家要的「到驿站了没有、要不要去取」只在这一档能回答。
     *
     * <p>⚠️ 加这一档时所有按本枚举分支的地方都要显式处理它 —— 落进 default 就被当成别的。
     * 消费方一律用<b>不带 default 的 switch 表达式</b>，让编译器当守卫。
     */
    DELIVERING,
    /** 已签收 */
    SIGNED,
    /** 异常（退回 / 滞留 / 拒收等） */
    EXCEPTION,
    /** 查不到或还没有轨迹 */
    UNKNOWN
}
