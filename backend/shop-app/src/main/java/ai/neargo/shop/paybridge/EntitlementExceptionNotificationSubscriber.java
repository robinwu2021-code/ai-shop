package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.ExceptionOpened;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.spi.platform.OpsStaffPort;
import ai.neargo.shop.spi.user.MerchantStaffPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Recipient resolution follows current owner/IAM facts; retrying delivery never calls an entitlement command. */
@Component
public class EntitlementExceptionNotificationSubscriber implements OutboxConsumer {
    private final MessageService messages;
    private final MerchantStaffPort merchants;
    private final OpsStaffPort operations;
    private final ObjectMapper json;
    public EntitlementExceptionNotificationSubscriber(MessageService messages,MerchantStaffPort merchants,
            OpsStaffPort operations,ObjectMapper json) {
        this.messages=messages; this.merchants=merchants; this.operations=operations; this.json=json;
    }
    @Override public boolean supports(String type) { return "ENTITLEMENT_EXCEPTION_OPENED".equals(type); }
    @Override public void consume(SysOutbox row) {
        if(!supports(row.getEventType())) throw new IllegalArgumentException("Unsupported exception notice");
        var event=json.readValue(row.getPayload(),ExceptionOpened.class);
        PayEntitlementEventCodec.validate(event);
        if(!event.eventNo().equals(row.getEventNo()) || !event.aggregateId().equals(row.getAggregateId())
                || !event.aggregateType().equals(row.getAggregateType())) {
            throw new IllegalArgumentException("Exception notification identity mismatch");
        }
        for(String user:merchants.staffUserNos(event.entityNo(),Set.of())) {
            deliver(event,MsgMessage.RECEIVER_STAFF,user,"权益发放待处理","有一笔权益发放尚未完成，请核实原单并联系运营处理。");
        }
        for(String staff:operations.staffNosWithPermForEntity(Perms.ENT_EXCEPTION_READ,event.entityNo())) {
            deliver(event,MsgMessage.RECEIVER_OPS,staff,"权益发放异常","有一笔权益发放异常，请在权益异常处理中核实原单。");
        }
    }
    private void deliver(ExceptionOpened event,String receiverType,String receiver,String title,String body) {
        if(receiver==null || receiver.isBlank() || receiver.length()>64) {
            throw new IllegalArgumentException("Invalid exception notice recipient");
        }
        messages.pushTo(receiverType,receiver,MessageService.TRADE,title,body,null,key(event.eventNo(),receiverType,receiver));
    }
    private static String key(String event,String type,String receiver) {
        try {
            return "EA"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((event+":"+type+":"+receiver).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
}
