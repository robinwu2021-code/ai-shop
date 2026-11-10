package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthority;
import java.time.Instant;

/** Immutable successor mapping; permanent booking/direct consumption remains on the original logical demand. */
public record ResourceWorkflowSegment(String schema, Request request, WorkflowAuthority authority,
        String previousSegmentNo, String resourceNo, int quantity, Instant startedAt, Instant expectedEndAt) {
    public static final String SCHEMA = "RESOURCE_OCCUPATION_WORKFLOW_SEGMENT_V1";
    public void validate() {
        request.validate(); authority.validate();
        if (!SCHEMA.equals(schema) || !request.authority().equals(authority.lookup())
                || !request.actionNo().equals(authority.actionNo()) || !request.payloadHash().equals(authority.actionHash())
                || !"HANDOVER".equals(authority.action().name()) || !startedAt.isBefore(expectedEndAt())
                || request.replacements().stream().noneMatch(r -> r.segmentNo().equals(previousSegmentNo)
                    && r.newResourceNo().equals(resourceNo)))
            throw new IllegalArgumentException("invalid resource successor original");
    }
}
