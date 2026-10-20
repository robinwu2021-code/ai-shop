package ai.neargo.shop.pay.entitlement.dto;

import java.time.LocalDateTime;
public final class ExceptionViews {
    private ExceptionViews() { }
    public record RecoveryCommand(long expectedExceptionRevision,String reason) {
        public RecoveryCommand {
            if(expectedExceptionRevision<0 || reason==null || reason.isBlank() || reason.length()>500) {
                throw new IllegalArgumentException("Recovery revision and reason required");
            }
        }
    }
    public record Evidence(String grantNo,String grantStatus,String sourceType,String reasonCode,String sourceStatus,DeliveryEvidence delivery) {
        public Evidence(String grantNo,String grantStatus,String sourceType,String reasonCode,String sourceStatus) {
            this(grantNo,grantStatus,sourceType,reasonCode,sourceStatus,null);
        }
        public Evidence(String grantNo,String grantStatus,String sourceType,String reasonCode) {
            this(grantNo,grantStatus,sourceType,reasonCode,null);
        }
    }
    public record DeliveryEvidence(String eventNo,String eventType,String status,int attempts) { }
    /** Global operator metadata only; payload, credentials and immutable hashes are intentionally absent. */
    public record DeadLetter(String eventNo,String eventType,int schemaVersion,String aggregateNo,int attempts,
                             LocalDateTime occurredAt,LocalDateTime updatedAt) { }
    public record DeadLetterPage(java.util.List<DeadLetter> records,String nextCursor) {
        public DeadLetterPage { records=java.util.List.copyOf(records); }
    }
    public record ExceptionView(String exceptionNo,String entityNo,String kind,String bizNo,String status,
                                Evidence evidence,String lastAction,long revision) { }
    /** Audit metadata only; durable request keys and internal response snapshots never leave pay. */
    public record ActionView(String actionNo,String exceptionNo,String operationKind,String status,String actorNo,
                             String reason,LocalDateTime createdAt,LocalDateTime updatedAt) { }
}
