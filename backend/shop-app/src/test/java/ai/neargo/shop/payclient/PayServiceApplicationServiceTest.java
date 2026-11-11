package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.PayServiceApplicationStore;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.payclient.impl.PayServiceApplicationServiceImpl;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Real H2 source locks/persistence, with a near-pay contract boundary double; no KYC or channel calls. */
class PayServiceApplicationServiceTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private PayServiceApplicationService service;
    private final PaySourceLinkApi api=mock(PaySourceLinkApi.class);
    private final Map<Long,String> acceptedEntities=new ConcurrentHashMap<>();
    private final Map<Long,PaySourceLinkApi.Profile> acceptedProfiles=new ConcurrentHashMap<>();
    private final Map<String,PaySourceLinkApi.Apply> acceptedEvents=new ConcurrentHashMap<>();
    private final List<PaySourceLinkApi.Apply> sent=java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean loseResponse=new AtomicBoolean();

    @BeforeEach void fixture() {
        source=new DriverManagerDataSource("jdbc:h2:mem:pay-service-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mch_entity(entity_no VARCHAR PRIMARY KEY,tenant_no VARCHAR,name VARCHAR,market VARCHAR,logo VARCHAR,"
                +"ownership_change_no VARCHAR,archived_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,deleted INT)");
        jdbc.execute("CREATE TABLE mch_store(id BIGINT PRIMARY KEY,store_no VARCHAR,entity_no VARCHAR,tenant_no VARCHAR,ownership_change_no VARCHAR,name VARCHAR,slug VARCHAR,banner_url VARCHAR,"
                +"open_hours VARCHAR,address VARCHAR,address_detail VARCHAR,status VARCHAR,deleted INT,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE mch_pay_service_application(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR,entity_no VARCHAR,source_caller VARCHAR,"
                +"source_scope VARCHAR,request_no VARCHAR,request_hash VARCHAR,profile_revision BIGINT,source_event_id VARCHAR,actor_realm VARCHAR,actor_no VARCHAR,"
                +"request_json CLOB,created_at TIMESTAMP,updated_at TIMESTAMP,created_by VARCHAR,updated_by VARCHAR,version BIGINT,deleted INT,"
                +"UNIQUE(tenant_no,source_caller,source_scope,request_no),UNIQUE(tenant_no,entity_no,source_caller,source_scope,profile_revision))");
        jdbc.update("INSERT INTO mch_entity VALUES('M1','MAIN','test shop','TW',NULL,NULL,NULL,?,?,0)",Timestamp.valueOf("2026-11-01 00:00:00"),Timestamp.valueOf("2026-11-01 00:00:00"));
        jdbc.update("INSERT INTO mch_entity SELECT 'M2',tenant_no,name,market,logo,ownership_change_no,archived_at,created_at,updated_at,deleted FROM mch_entity WHERE entity_no='M1'");
        jdbc.update("INSERT INTO mch_store VALUES(1,'ST1','M1','MAIN',NULL,'test store',NULL,NULL,NULL,'test address',NULL,'ACTIVE',0,?,?)",Timestamp.valueOf("2026-11-01 00:00:00"),Timestamp.valueOf("2026-11-01 00:00:00"));
        when(api.apply(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            PaySourceLinkApi.Apply body=call.getArgument(0);sent.add(body);
            // Actual near-pay rejects different content under one source profile revision, and event-body drift.
            var entity=acceptedEntities.putIfAbsent(body.entityRevision(),body.entityName()+"|"+body.profile().registrationNo());
            var profile=acceptedProfiles.putIfAbsent(body.profileRevision(),body.profile());
            var event=acceptedEvents.putIfAbsent(body.sourceEventId(),body);
            if(entity!=null && !entity.equals(body.entityName()+"|"+body.profile().registrationNo())
                    || profile!=null && !profile.equals(body.profile()) || event!=null && !event.equals(body))
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR,"PAY",409,"source version conflict");
            if(loseResponse.getAndSet(false)) throw new ServiceCallException(CallOutcome.TIMEOUT,"PAY",0,"response lost");
            return new PaySourceLinkApi.Result("PM-test",List.of(),null,"ACCEPTED");
        });
        service=application();
    }
    private PayServiceApplicationService application() {
        var factory=new StaticListableBeanFactory(Map.of("pay",api));
        return new PayServiceApplicationServiceImpl(new PayServiceApplicationStore(source,new DataSourceTransactionManager(source)),
                new StoreSnapshotReader(source),PayServiceApplyRequestsTest.PROPS,factory.getBeanProvider(PaySourceLinkApi.class),JsonMapper.builder().build());
    }
    private PayServiceApplyRequests.ApplyReq form(String phone,String key) {
        return new PayServiceApplyRequests.ApplyReq("TESTONLY company","TESTONLY",null,phone,null,key);
    }
    private void apply(String phone,String key) { service.apply("M1",form(phone,key),"OPERATOR","OPS1"); }

    @Test void changedProfileAndAToBToAAllocateNewMonotoneRevisionsWithoutEditingEntityTimestamp() {
        var before=jdbc.queryForObject("SELECT updated_at FROM mch_entity WHERE entity_no='M1'",Timestamp.class);
        apply("A","first");apply("B","correction");apply("A","third");
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1).profileRevision()).isGreaterThan(sent.get(0).profileRevision());
        assertThat(sent.get(2).profileRevision()).isGreaterThan(sent.get(1).profileRevision());
        assertThat(sent.stream().map(PaySourceLinkApi.Apply::sourceEventId).distinct().count()).isEqualTo(3);
        assertThat(acceptedProfiles).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM mch_entity WHERE entity_no='M1'",Timestamp.class)).isEqualTo(before);
    }
    @Test void correctedRegistrationAdvancesTheRealSourceEntityAndProfileVersionsTogether() {
        apply("A","first");
        service.apply("M1",new PayServiceApplyRequests.ApplyReq("TESTONLY company","TESTONLY-corrected",null,"A",null,"correction"),"OPERATOR","OPS1");
        assertThat(sent.getLast().entityRevision()).isGreaterThan(sent.getFirst().entityRevision());
        assertThat(sent.getLast().profileRevision()).isEqualTo(sent.getLast().entityRevision());
    }
    @Test void lostResponseReplaysExactPersistedPayloadAfterRestartAndStoreSnapshotChanges() {
        loseResponse.set(true);
        assertThatThrownBy(()->apply("A","original")).isInstanceOf(BizException.class);
        var first=sent.getFirst();
        jdbc.update("UPDATE mch_entity SET updated_at=? WHERE entity_no='M1'",Timestamp.valueOf("2026-11-02 00:00:00"));
        jdbc.update("UPDATE mch_store SET name='changed',updated_at=? WHERE store_no='ST1'",Timestamp.valueOf("2026-11-03 00:00:00"));
        service=application();apply("A","original");
        assertThat(sent.getLast()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isOne();
    }
    @Test void sameRequestRejectsChangedPayloadActorAndTargetBeforeExternalIo() {
        apply("A","original");
        assertConflict(()->apply("B","original"));
        assertConflict(()->service.apply("M1",form("A","original"),"OPERATOR","OPS2"));
        assertConflict(()->service.apply("M2",form("A","original"),"OPERATOR","OPS1"));
        assertThat(sent).hasSize(1);
    }
    @Test void newCommandWithUnchangedProfileFreezesNewStoreAndActorInANewEvent() {
        apply("A","first");
        jdbc.update("UPDATE mch_store SET name='changed',updated_at=? WHERE store_no='ST1'",Timestamp.valueOf("2026-11-03 00:00:00"));
        service.apply("M1",form("A","new-command"),"OPERATOR","OPS2");
        assertThat(sent.getLast().profile()).isEqualTo(sent.getFirst().profile());
        assertThat(sent.getLast().stores()).isNotEqualTo(sent.getFirst().stores());
        assertThat(sent.getLast().actorRef().subject()).isEqualTo("OPS2");
        assertThat(sent.getLast().sourceEventId()).isNotEqualTo(sent.getFirst().sourceEventId());
    }
    @Test void concurrentCommandsSerializeOnRealEntityRoot() throws Exception {
        try(var threads=Executors.newFixedThreadPool(2)) {
            var one=threads.submit(()->apply("A","concurrent-a"));
            var two=threads.submit(()->apply("B","concurrent-b"));
            one.get();two.get();
        }
        assertThat(sent).hasSize(2);
        assertThat(sent.stream().map(PaySourceLinkApi.Apply::profileRevision).distinct().count()).isEqualTo(2);
    }
    @Test void missingKeyAndCurrentOwnershipFreezeCreateNoSubmissionAndMakeNoExternalCall() {
        assertThatThrownBy(()->apply("A",null)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        jdbc.update("UPDATE mch_entity SET ownership_change_no='claim' WHERE entity_no='M1'");
        assertConflict(()->apply("A","frozen"));
        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isZero();
    }
    @Test void currentStoreClaimAlsoFreezesTheWholeApplication() {
        jdbc.update("UPDATE mch_store SET ownership_change_no='claim' WHERE store_no='ST1'");
        assertConflict(()->apply("A","store-frozen"));
        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isZero();
    }
    private void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
