package ai.neargo.shop.spi.approval;

import ai.neargo.shop.event.DomainEvent;

/** 送审请求（发件箱事件）。只带关联键，投递时按当前快照组装。 */
public record ApprovalSubmitRequested(String subjectType, String subjectRef, long revision) implements DomainEvent {

    public static final String TYPE = "APPROVAL_SUBMIT_REQUESTED";

    @Override
    public String aggregateType() {
        return "APPROVAL";
    }

    @Override
    public String aggregateId() {
        return subjectType + ":" + subjectRef;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
