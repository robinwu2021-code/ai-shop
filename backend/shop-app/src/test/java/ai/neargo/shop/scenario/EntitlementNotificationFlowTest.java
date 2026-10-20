package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.paybridge.EntitlementNotificationSubscriber;
import ai.neargo.shop.paybridge.EntitlementExceptionNotificationSubscriber;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.ExceptionOpened;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementGranted;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueCredited;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ent-notifications;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EntitlementNotificationFlowTest {
    @Autowired EntitlementNotificationSubscriber notifications;
    @Autowired EntitlementExceptionNotificationSubscriber exceptions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ai.neargo.shop.message.MessageService messages;
    private String id() { return UUID.randomUUID().toString().replace("-",""); }
    private SysOutbox row(PayEntitlementEvent event) {
        var row=new SysOutbox(); row.setEventNo(event.eventNo()); row.setEventType(event.eventType());
        row.setAggregateId(event.aggregateId()); row.setAggregateType(event.aggregateType());
        row.setPayload(json.writeValueAsString(event)); return row;
    }
    @Test void committedFactsCreateOnePrivateInboxMessagePerEvent() {
        String user=id(); long at=System.currentTimeMillis();
        var events=java.util.List.of(
                new EntitlementGranted(id(),id(),user,"M1","V1","PAID_CARD_ORDER","O1",at),
                new EntitlementUsed(id(),id(),user,"M1","S1","STORED_VALUE","A1",100L,null,at),
                new StoredValueCredited(id(),id(),"A1",user,"M1","S1",1000,1200,"CNY",at));
        for(var event:events) { notifications.consume(row(event)); notifications.consume(row(event)); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?",Integer.class,user))
                .isEqualTo(3);
        var bodies=jdbc.queryForList("SELECT body FROM notify_message WHERE receiver_no=?",String.class,user);
        assertThat(String.join("",bodies)).doesNotContain("本金","赠金","1200","A1");
    }
    @Test void legacyOrderUsageAndReturnNotifyOnceWithoutInventingAStoreOrCashPayment() {
        String user=id();long at=System.currentTimeMillis();
        for(String status:java.util.List.of("COMMITTED","REVERSED")) {
            String event=id(),usage=id();
            var row=new SysOutbox();row.setEventNo(event);row.setEventType("LEGACY_COUPON_USAGE_CHANGED");
            row.setAggregateType("ENTITLEMENT_USAGE");row.setAggregateId(usage);
            row.setPayload(json.writeValueAsString(java.util.Map.of("eventNo",event,"usageNo",usage,"userNo",user,
                    "entityNo","M1","instanceNo","I1","orderNo","O1","status",status,"occurredAt",at)));
            assertThat(notifications.supports(row.getEventType())).isTrue();
            notifications.consume(row);notifications.consume(row);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?",Integer.class,user)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT title FROM notify_message WHERE receiver_no=?",String.class,user))
                .containsExactlyInAnyOrder("核销成功","核销已退回");
    }
    @Test void alteredHeaderAndMissingRecipientAreRejectedBeforeDelivery() {
        String user=id();
        var event=new EntitlementGranted(id(),id(),user,"M1","V1","CENTER","O1",System.currentTimeMillis());
        var altered=row(event); altered.setAggregateId("OTHER");
        assertThatThrownBy(() -> notifications.consume(altered)).isInstanceOf(IllegalArgumentException.class);
        var invalid=new EntitlementGranted(id(),id(),null,"M1","V1","CENTER","O1",System.currentTimeMillis());
        assertThatThrownBy(() -> notifications.consume(row(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?",Integer.class,user)).isZero();
    }
    @Test void deliveryFailureRetriesOnlyMessageAndLeavesAssetsUntouched() {
        String user=id();
        var message=org.mockito.Mockito.mock(ai.neargo.shop.message.MessageService.class);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if(attempts.getAndIncrement()==0) throw new IllegalStateException("Transient message failure");
            messages.push(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),
                    call.getArgument(4),call.getArgument(5));
            return null;
        }).when(message).push(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyString());
        var subscriber=new EntitlementNotificationSubscriber(message,json);
        var event=row(new StoredValueCredited(id(),id(),"A1",user,"M1","S1",1000,1200,"CNY",System.currentTimeMillis()));
        assertThatThrownBy(() -> subscriber.consume(event)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?",Integer.class,user)).isZero();
        subscriber.consume(event); subscriber.consume(event);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_no=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE user_no=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?",Integer.class,user)).isZero();
    }
    @Test void exceptionNoticeReachesOwnerAndAuthorizedOperationsOnceAndIsVisibleInInAppLog() {
        String owner=id(),entity=id();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                + "VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",owner);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES(?,?,'beauty','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity,owner);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,status,tenant_no,created_at,updated_at) "
                + "VALUES(?,?,?,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),entity,owner);
        String admin=jdbc.queryForObject("SELECT staff_no FROM sys_ops_staff WHERE username='admin'",String.class);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_type='OPS' AND receiver_no=?",
                Integer.class,admin);
        var event=row(new ExceptionOpened(id(),id(),entity,"GRANT_DELIVERY",id(),System.currentTimeMillis()));
        exceptions.consume(event); exceptions.consume(event);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_type='STAFF' AND receiver_no=?",
                Integer.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE receiver_type='OPS' AND receiver_no=?",
                Integer.class,admin)).isEqualTo(before+1);
        assertThat(messages.opsInAppMessages("STAFF",owner,null,null,1,20).records()).hasSize(1);
        var altered=row(new ExceptionOpened(id(),id(),entity,"GRANT_DELIVERY",id(),System.currentTimeMillis()));
        altered.setAggregateId("OTHER");
        assertThatThrownBy(() -> exceptions.consume(altered)).isInstanceOf(IllegalArgumentException.class);
    }
}
