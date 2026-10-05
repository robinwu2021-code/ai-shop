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
    /** 运输中（发出 / 到达中转 / 派送中，统一成一档，端上不必区分那么细） */
    IN_TRANSIT,
    /** 已签收 */
    SIGNED,
    /** 异常（退回 / 滞留 / 拒收等） */
    EXCEPTION,
    /** 查不到或还没有轨迹 */
    UNKNOWN
}
