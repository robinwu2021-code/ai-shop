package ai.neargo.shop.invoice.gateway;

/** 服务商能配给哪些期别的号段（TDD-发票-供应商能力定义完善 D6）：窗口外领域直接拒绝，不打服务商。 */
public enum RangeWindow {
    CURRENT, NEXT, ANY
}
