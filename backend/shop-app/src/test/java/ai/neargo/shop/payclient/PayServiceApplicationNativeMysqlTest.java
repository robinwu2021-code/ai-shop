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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Native V660 installation and source-owner locking; PAY is a boundary double, never a KYC/channel call. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class PayServiceApplicationNativeMysqlTest {
    private static final String MIGRATION="V660__merchant_pay_service_application.sql";
    @TempDir Path migrations;
    private JdbcTemplate admin;
    private String database;
    private DriverManagerDataSource source;
    private String sourceUrl;
    private JdbcTemplate jdbc;
    private PayServiceApplicationService service;
    private final PaySourceLinkApi api=mock(PaySourceLinkApi.class);
    private final List<PaySourceLinkApi.Apply> sent=Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean loseResponse=new AtomicBoolean();

    @BeforeEach void installRealMigration() throws Exception {
        String url=System.getenv("COMMERCE_MYSQL_URL"),user=System.getenv("COMMERCE_MYSQL_USER"),
                password=System.getenv("COMMERCE_MYSQL_PASSWORD");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        assertThat(admin.queryForObject("SELECT VERSION()",String.class)).startsWith("9.").doesNotContainIgnoringCase("mariadb");
        String name="pay_application_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        database=name;
        sourceUrl=url.replace("/?","/"+database+"?");
        source=new DriverManagerDataSource(sourceUrl,user,password);
        jdbc=new JdbcTemplate(source);
        try(var stream=new ClassPathResource("db/migration/"+MIGRATION).getInputStream()) {
            Files.copy(stream,migrations.resolve(MIGRATION));
        }
        // Only this genuine forward migration is needed; no synthetic baseline/history marker is inserted.
        Flyway.configure().dataSource(source).locations("filesystem:"+migrations).load().migrate();
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL",String.class))
                .isEqualTo("660");
        String schema=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("mch_entity","mch_store")) {
            var ddl=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\n\\);").matcher(schema);
            assertThat(ddl.find()).as("existing %s physical root",table).isTrue();
            jdbc.execute(ddl.group());
        }
        jdbc.update("INSERT INTO mch_entity(entity_no,name,market,created_at,updated_at)"
                +" VALUES('M1','TESTONLY entity','TW','2026-11-01 00:00:00','2026-11-01 00:00:00')");
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,commerce_model,business_type_code,created_at,updated_at)"
                +" VALUES('ST1','M1','TESTONLY store','STORE_V1','RETAIL','2026-11-01 00:00:00','2026-11-01 00:00:00')");
        when(api.apply(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.add(call.getArgument(0));
            if(loseResponse.getAndSet(false)) throw new ServiceCallException(CallOutcome.TIMEOUT,"PAY",0,"response lost");
            return new PaySourceLinkApi.Result("PM-TESTONLY",List.of(),null,"ACCEPTED");
        });
        service=application();
    }

    private PayServiceApplicationService application() {
        var beans=new StaticListableBeanFactory(Map.of("pay",api));
        return new PayServiceApplicationServiceImpl(new PayServiceApplicationStore(source,new DataSourceTransactionManager(source)),
                new StoreSnapshotReader(source),PayServiceApplyRequestsTest.PROPS,beans.getBeanProvider(PaySourceLinkApi.class),
                JsonMapper.builder().build());
    }
    private PaySourceLinkApi.Result apply(String phone,String key) {
        return service.apply("M1",new PayServiceApplyRequests.Form("TESTONLY entity","TESTONLY",null,phone,null,key),"OPERATOR","OPS1");
    }

    @Test void uncertainResponseReplaysTheImmutableOriginalAfterRestartAndSnapshotChange() {
        loseResponse.set(true);
        assertThatThrownBy(()->apply("A","original")).isInstanceOf(BizException.class);
        var original=sent.getFirst();
        String bytes=jdbc.queryForObject("SELECT request_json FROM mch_pay_service_application",String.class);
        jdbc.update("UPDATE mch_entity SET name='new entity',updated_at='2026-11-02 00:00:00' WHERE entity_no='M1'");
        jdbc.update("UPDATE mch_store SET name='new store',updated_at='2026-11-03 00:00:00' WHERE store_no='ST1'");
        service=application();apply("A","original");
        assertThat(sent).containsExactly(original,original);
        assertThat(jdbc.queryForObject("SELECT request_json FROM mch_pay_service_application",String.class)).isEqualTo(bytes);
        assertConflict(()->apply("B","original"));
        assertThat(sent).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isOne();
    }

    @Test void nativeRootLockSerializesDifferentCommandsAndIdenticalKeyContenders() throws Exception {
        race("same","same");
        assertThat(sent.getFirst()).isEqualTo(sent.getLast());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isOne();
        race("next-a","next-b");
        var revisions=jdbc.queryForList("SELECT profile_revision FROM mch_pay_service_application ORDER BY profile_revision",Long.class);
        assertThat(revisions).hasSize(3);
        assertThat(revisions.get(1)).isEqualTo(revisions.getFirst()+1);
        assertThat(revisions.getLast()).isEqualTo(revisions.getFirst()+2);
        assertThat(jdbc.queryForObject("SELECT updated_at='2026-11-01 00:00:00' FROM mch_entity WHERE entity_no='M1'",Boolean.class)).isTrue();
    }
    private void race(String first,String second) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var jobs=List.of(first,second).stream().map(key->workers.submit(()->{
                ready.countDown();
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
                return apply("A",key);
            })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var job:jobs) assertThat(job.get(20,TimeUnit.SECONDS).merchantRef()).isEqualTo("PM-TESTONLY");
        } finally { start.countDown(); }
    }

    @Test void genuineChecksUniqueKeysAndPermanentOriginalTriggersRejectInvalidWrites() {
        apply("A","original");
        long revision=sent.getFirst().profileRevision();
        String event=sent.getFirst().sourceEventId();
        String bytes=jdbc.queryForObject("SELECT request_json FROM mch_pay_service_application",String.class);
        assertThatThrownBy(()->copy("original",revision+1,event+"-new",bytes)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(()->copy("new",revision,event+"-new",bytes)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(()->copy("new",revision+1,event,bytes)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(()->copy("new",revision+1,event+"-new","{")).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_mpsa_json");
        assertThatThrownBy(()->jdbc.update("UPDATE mch_pay_service_application SET request_json='{}'"))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Payment service submission original is immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE mch_pay_service_application SET deleted=1"))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Payment service submission original is immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM mch_pay_service_application"))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Payment service submission original is permanent");
        assertThat(jdbc.queryForObject("SELECT request_json FROM mch_pay_service_application",String.class)).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isOne();
    }
    @Test void normalDmlAccountCanSubmitAndReplayWithoutTriggerMetadataPrivileges() {
        String appUser="payapp_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        String password=UUID.randomUUID().toString();
        jdbc.execute("CREATE USER '"+appUser+"'@'%' IDENTIFIED BY '"+password+"'");
        try {
            jdbc.execute("GRANT SELECT,INSERT,UPDATE ON `"+database+"`.* TO '"+appUser+"'@'%'");
            source=new DriverManagerDataSource(sourceUrl,appUser,password);
            var restricted=new JdbcTemplate(source);
            assertThat(restricted.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",Integer.class)).isZero();
            service=application();apply("A","restricted-original");apply("A","restricted-original");
            assertThat(sent).hasSize(2);
            assertThat(sent.getLast()).isEqualTo(sent.getFirst());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_pay_service_application",Integer.class)).isOne();
            assertThatThrownBy(()->restricted.update("UPDATE mch_pay_service_application SET request_json='{}'"))
                    .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Payment service submission original is immutable");
        } finally {
            jdbc.execute("DROP USER '"+appUser+"'@'%'");
        }
    }
    private void copy(String key,long revision,String event,String json) {
        jdbc.update("INSERT INTO mch_pay_service_application(tenant_no,entity_no,source_caller,source_scope,request_no,"
                +"request_hash,profile_revision,source_event_id,actor_realm,actor_no,request_json,created_at,updated_at,created_by,updated_by,version,deleted)"
                +" SELECT tenant_no,entity_no,source_caller,source_scope,?,request_hash,?,?,actor_realm,actor_no,?,"
                +"created_at,updated_at,created_by,updated_by,version,deleted FROM mch_pay_service_application WHERE request_no='original'",
                key,revision,event,json);
    }
    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @AfterEach void dropOnlyThisTestDatabase() {
        if(admin!=null && database!=null) admin.execute("DROP DATABASE `"+database+"`");
    }
}
