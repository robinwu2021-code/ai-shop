package ai.neargo.shop.invoice.gateway;

/**
 * 供应商动作的标准名（TDD-发票-加值中心模板-鲸跃与汎宇 §1）。领域、码表与留痕只认它；厂商在自己的类里映射成接口名 / 功能码。
 *
 * <p>上传类（{@link #ISSUE} … {@link #ALLOWANCE_VOID}）的名字与上传任务的 {@code messageType} 一致，走 {@code send}；
 * 其余是能力类动作，走基础类的 {@code call} 模板。
 */
public enum ProviderOperation {
    ISSUE, VOID, ALLOWANCE, ALLOWANCE_VOID,
    VERIFY, RANGE, QUERY,
    TRACK_MODE, TRACK_QUERY, TRACK_UPLOAD, BLANK_REPORT;

    /** 上传任务的 messageType → 标准动作；不认识的返回 null。 */
    public static ProviderOperation ofMessageType(String messageType) {
        for (var op : values()) if (op.name().equals(messageType)) return op;
        return null;
    }
}
