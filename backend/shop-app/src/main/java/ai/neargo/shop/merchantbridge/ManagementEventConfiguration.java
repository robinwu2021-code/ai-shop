package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.event.*;
import ai.neargo.shop.event.outbox.*;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
/** Uses the configured delivery engine without enabling a second engine or changing global mode. */
@Configuration(proxyBeanMethods=false)
public class ManagementEventConfiguration {
 @Bean("managementOutboxIngress") OutboxIngress ingress(UnifiedOutboxFacade facade,OutboxEventBus events,ObjectMapper json){return envelope->{
  if(facade.enabled("PLATFORM"))return facade.accept("PLATFORM",envelope);
  var body=json.readTree(envelope.payload());
  @SuppressWarnings("unchecked") Map<String,Object> result=json.convertValue(body.get("resultSnapshot"),Map.class);
  events.publish(envelope.eventNo(),new ManagementChanged(body.path("tenantNo").asString(),body.path("actorKey").asString(),body.path("action").asString(),body.path("target").asString(),body.path("commandHash").asString(),result));
  return new PublishReceipt(envelope.eventNo(),true);
 };}
 public record ManagementChanged(String tenantNo,String actorKey,String action,String target,String commandHash,Map<String,Object> resultSnapshot) implements DomainEvent {
  public String aggregateType(){return "MANAGEMENT";}
  public String aggregateId(){return tenantNo+"|"+target;}
  public String eventType(){return "MANAGEMENT_CHANGED";}
 }
}
