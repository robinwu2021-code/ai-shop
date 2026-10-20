package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementGranted;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.LegacyCouponUsageChanged;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueCredited;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Receives committed facts only. Message retries never invoke any financial or entitlement command. */
@Component
public class EntitlementNotificationSubscriber implements OutboxConsumer {
    private final MessageService messages;
    private final ObjectMapper json;
    public EntitlementNotificationSubscriber(MessageService messages,ObjectMapper json) {
        this.messages=messages; this.json=json;
    }
    @Override public boolean supports(String type) {
        return Set.of("ENTITLEMENT_GRANTED","ENTITLEMENT_USED","STORED_VALUE_CREDITED","LEGACY_COUPON_USAGE_CHANGED").contains(type);
    }
    @Override public void consume(SysOutbox row) {
        PayEntitlementEvent event;
        String user,title,body;
        switch(row.getEventType()) {
            case "ENTITLEMENT_GRANTED" -> {
                var e=json.readValue(row.getPayload(),EntitlementGranted.class); event=e; user=e.userNo();
                title="权益已到账"; body="您获得的权益已到账，可在会员权益中查看。";
            }
            case "ENTITLEMENT_USED" -> {
                var e=json.readValue(row.getPayload(),EntitlementUsed.class); event=e; user=e.userNo();
                title="核销成功"; body="本次消费已核销，可在消费记录中查看。";
            }
            case "LEGACY_COUPON_USAGE_CHANGED" -> {
                var e=json.readValue(row.getPayload(),LegacyCouponUsageChanged.class);event=e;user=e.userNo();
                boolean returned="REVERSED".equals(e.status());
                title=returned?"核销已退回":"核销成功";
                body=returned?"本次订单优惠核销已退回，可在消费记录中查看。":"本次订单优惠已核销，可在消费记录中查看。";
            }
            case "STORED_VALUE_CREDITED" -> {
                var e=json.readValue(row.getPayload(),StoredValueCredited.class); event=e; user=e.userNo();
                title="储值已到账"; body="本次储值已到账，可在门店储值记录中查看。";
            }
            default -> throw new IllegalArgumentException("Unsupported entitlement notification");
        }
        PayEntitlementEventCodec.validate(event);
        if(!event.eventNo().equals(row.getEventNo()) || !event.aggregateId().equals(row.getAggregateId())
                || !event.aggregateType().equals(row.getAggregateType())) {
            throw new IllegalArgumentException("Notification event identity mismatch");
        }
        messages.push(user,MessageService.TRADE,title,body,null,key(event.eventNo(),user));
    }
    private static String key(String no,String user) {
        try {
            return "EN"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((no+":USER:"+user).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
}
