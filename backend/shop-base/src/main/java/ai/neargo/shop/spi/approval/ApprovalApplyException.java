package ai.neargo.shop.spi.approval;

/** 执行节点上业务拒绝生效。{@code code} 回给 OpsKit，显示在审批详情的「生效失败」上。 */
public class ApprovalApplyException extends RuntimeException {

    /** 对象在送审后被改过，或已不在待审态。 */
    public static final String STALE = "STALE";

    private final String code;

    public ApprovalApplyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
