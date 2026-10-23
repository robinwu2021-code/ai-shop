package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.event.*;
import org.springframework.stereotype.Component;
/** The durable management event is the audit truth; no lossy secondary write is required for BR07. */
@Component
public class ManagementAuditConsumer implements OutboxConsumer {
 public String handlerId(){return "management-audit-v1";}
 public boolean supports(String type){return "MANAGEMENT_CHANGED".equals(type);}
 public void consume(SysOutbox event){if(event.getPayload()==null)throw new IllegalStateException("Missing management audit payload");}
}
