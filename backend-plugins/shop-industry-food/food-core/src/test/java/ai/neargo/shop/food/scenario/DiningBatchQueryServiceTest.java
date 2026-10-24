package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.mapper.DiningBatchQueryMapper;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.service.impl.DiningBatchQueryServiceImpl;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real SQL exercises V1 detached_at semantics, global precedence and one source read snapshot. */
class DiningBatchQueryServiceTest extends FoodWriteGuardFixture {
    static final LocalDate DATE=LocalDate.of(2026,10,24);
    void run(ContextConsumer<AssertableApplicationContext> test) { runner().withUserConfiguration(Queries.class).run(test); }
    Fixture fixture(AssertableApplicationContext c) {
        var f=prepare(c);
        f.sql().execute("CREATE TABLE fnb_order_batch(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                +"entity_no VARCHAR(32),store_no VARCHAR(32),deleted INT DEFAULT 0,batch_no VARCHAR(32) UNIQUE,check_no VARCHAR(32),"
                +"status VARCHAR(24),source VARCHAR(24),business_date DATE,fulfillment VARCHAR(16),pay_mode VARCHAR(16),"
                +"quote_ref VARCHAR(64),quote_hash VARCHAR(64),created_at DATETIME(3),accepted_at DATETIME(3),"
                +"scheduled_at DATETIME(3),version BIGINT DEFAULT 0,frozen_request VARCHAR(10000),buyer_no VARCHAR(32),"
                +"operator_no VARCHAR(32),payer_ref VARCHAR(64))");
        f.sql().execute("CREATE TABLE fnb_check(tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"store_no VARCHAR(32),check_no VARCHAR(32),deleted INT DEFAULT 0)");
        f.sql().execute("CREATE TABLE fnb_check_batch_membership(tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"batch_no VARCHAR(32),check_no VARCHAR(32),attached_at DATETIME(3),detached_at DATETIME(3),deleted INT DEFAULT 0)");
        f.sql().execute("CREATE TABLE fnb_core_order_binding(tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"batch_no VARCHAR(32),binding_no VARCHAR(32),payment_root_no VARCHAR(32),store_order_no VARCHAR(32),"
                +"status VARCHAR(16),deleted INT DEFAULT 0)");
        f.sql().update("INSERT INTO fnb_check(entity_no,store_no,check_no) VALUES('E','A','OLD'),('E','A','NEW'),"
                +"('E','B','FOREIGN_STORE'),('OTHER','A','OTHER_ENTITY')");
        return f;
    }
    void batch(Fixture f,String number,String check) {
        f.sql().update("INSERT INTO fnb_order_batch(entity_no,store_no,batch_no,check_no,status,source,business_date,"
                +"fulfillment,pay_mode,quote_ref,quote_hash,created_at,frozen_request,buyer_no,operator_no,payer_ref) "
                +"VALUES('E','A',?,?,'BOUND','POS',?,'DINE_IN','POSTPAY','ORIGINAL_QUOTE','original-hash',?,"
                +"'frozen-private','buyer-private','operator-private','payer-private')",number,check,DATE,
                Timestamp.from(Instant.parse("2026-10-24T10:00:00Z")));
    }
    void membership(Fixture f,String number,String check,String tenant,String entity,boolean detached,int deleted) {
        f.sql().update("INSERT INTO fnb_check_batch_membership(tenant_no,entity_no,batch_no,check_no,attached_at,detached_at,deleted) "
                +"VALUES(?,?,?,?,?,?,?)",tenant,entity,number,check,Timestamp.from(Instant.parse("2026-10-24T10:00:00Z")),
                detached?Timestamp.from(Instant.parse("2026-10-24T11:00:00Z")):null,deleted);
    }
    ListDiningBatchesQuery current(String check) { return new ListDiningBatchesQuery(null,null,null,check,null,null,null); }
    @Test void liveMembershipOverridesOriginalGloballyAndDetachedOrDeletedMembershipFallsBack() {
        run(c -> {
            var f=fixture(c); var s=c.getBean(DiningBatchQueryService.class);
            for(String number:List.of("B1","B2","B3","B4","B5","B6","B7")) batch(f,number,"OLD");
            membership(f,"B1","NEW","MAIN","E",false,0);
            membership(f,"B1","NEW","MAIN","E",false,0);
            membership(f,"B3","NEW","MAIN","E",true,0);
            membership(f,"B4","FOREIGN_STORE","MAIN","E",false,0);
            membership(f,"B5","NEW","OTHER","E",false,0);
            membership(f,"B5","NEW","MAIN","OTHER",false,0);
            f.sql().update("INSERT INTO fnb_check(entity_no,store_no,check_no,deleted) VALUES('E','A','DELETED_CHECK',1)");
            membership(f,"B6","MISSING","MAIN","E",false,0);
            membership(f,"B6","DELETED_CHECK","MAIN","E",false,0);
            membership(f,"B7","NEW","MAIN","E",false,1);
            assertThat(s.list("E","A","staff",current("OLD")).records())
                    .extracting(DiningBatchSummaryVO::batchNo).containsExactly("B7","B5","B3","B2");
            var moved=s.list("E","A","staff",current("NEW"));
            assertThat(moved.total()).isOne(); assertThat(moved.records().getFirst().currentCheckNos()).containsExactly("NEW");
            assertThat(moved.records().getFirst().originalCheckNo()).isEqualTo("OLD");
            assertThat(moved.records().getFirst().membershipAuthoritative()).isTrue();
            assertThat(s.list("E","A","staff",current("FOREIGN_STORE")).records()).isEmpty();
            var all=s.list("E","A","staff",null).records();
            assertThat(all.stream().filter(row -> List.of("B4","B6").contains(row.batchNo())))
                    .allSatisfy(row -> { assertThat(row.membershipAuthoritative()).isTrue();
                        assertThat(row.currentCheckNos()).isEmpty(); });
        });
    }
    @Test void countAndStablePagesUseIdenticalOriginalCurrentStatusDateAndSourceFilters() {
        run(c -> {
            var f=fixture(c); var s=c.getBean(DiningBatchQueryService.class);
            for(String number:List.of("B1","B2","B3","B4")) {
                batch(f,number,"OLD"); membership(f,number,"NEW","MAIN","E",false,0);
            }
            batch(f,"EXCLUDED_STATUS","OLD");
            f.sql().update("UPDATE fnb_order_batch SET status='FAILED' WHERE batch_no='EXCLUDED_STATUS'");
            var first=s.list("E","A","staff",new ListDiningBatchesQuery(1,2,"OLD","NEW","BOUND",DATE,"POS"));
            var second=s.list("E","A","staff",new ListDiningBatchesQuery(2,2,"OLD","NEW","BOUND",DATE,"POS"));
            assertThat(first.total()).isEqualTo(4); assertThat(second.total()).isEqualTo(4);
            assertThat(first.records()).extracting(DiningBatchSummaryVO::batchNo).containsExactly("B4","B3");
            assertThat(second.records()).extracting(DiningBatchSummaryVO::batchNo).containsExactly("B2","B1");
            assertThat(s.list("E","A","staff",new ListDiningBatchesQuery(3,2,"OLD","NEW","BOUND",DATE,"POS"))
                    .records()).isEmpty();
        });
    }
    @Test void sourceBindingSummaryNeverLeaksCredentialsOrInventsCurrentMoneyAndPaymentState() {
        run(c -> {
            var f=fixture(c); batch(f,"B1","OLD");
            f.sql().update("INSERT INTO fnb_core_order_binding(entity_no,batch_no,binding_no,payment_root_no,store_order_no,status) "
                    +"VALUES('E','B1','ORIGINAL_BINDING','ORIGINAL_ROOT','ORIGINAL_SUB','BOUND'),"
                    +"('OTHER','B1','FOREIGN_BINDING','FOREIGN_ROOT','FOREIGN_SUB','BOUND')");
            f.sql().update("INSERT INTO fnb_core_order_binding(tenant_no,entity_no,batch_no,binding_no,payment_root_no,"
                    +"store_order_no,status,deleted) VALUES('OTHER','E','B1','TENANT_BINDING','TENANT_ROOT','TENANT_SUB','BOUND',0),"
                    +"('MAIN','E','B1','DELETED_BINDING','DELETED_ROOT','DELETED_SUB','BOUND',1)");
            var result=c.getBean(DiningBatchQueryService.class).list("E","A","staff",null);
            assertThat(result.records().getFirst().quoteNo()).isEqualTo("ORIGINAL_QUOTE");
            assertThat(result.records().getFirst().originalBindings()).hasSize(1);
            assertThat(result.records().getFirst().originalBindings().getFirst().paymentRootNo()).isEqualTo("ORIGINAL_ROOT");
            assertThat(c.getBean(ObjectMapper.class).writeValueAsString(result)).doesNotContain("buyer-private","operator-private",
                    "payer-private","frozen-private","FOREIGN_ROOT","amountMinor","currentDue","paymentStatus");
        });
    }
    @Test void mainEntityStoreAndLogicalDeletionRemainMandatoryAcrossEverySourceQuery() {
        run(c -> {
            var f=fixture(c); batch(f,"GOOD","OLD");
            for(String number:List.of("FOREIGN_ENTITY","FOREIGN_STORE","FOREIGN_TENANT","DELETED")) batch(f,number,"OLD");
            f.sql().update("UPDATE fnb_order_batch SET entity_no='OTHER' WHERE batch_no='FOREIGN_ENTITY'");
            f.sql().update("UPDATE fnb_order_batch SET store_no='B' WHERE batch_no='FOREIGN_STORE'");
            f.sql().update("UPDATE fnb_order_batch SET tenant_no='OTHER' WHERE batch_no='FOREIGN_TENANT'");
            f.sql().update("UPDATE fnb_order_batch SET deleted=1 WHERE batch_no='DELETED'");
            var s=c.getBean(DiningBatchQueryService.class);
            assertThat(s.list("E","A","staff",null).records()).extracting(DiningBatchSummaryVO::batchNo).containsExactly("GOOD");
            DiningSessionTest.code(() -> s.list("OTHER","A","staff",null),ErrorCode.FORBIDDEN);
        });
    }
    @Test void countAndPageObserveOneSnapshotWhenAnIndependentWriterCommitsBetweenThem() {
        run(c -> {
            var f=fixture(c); batch(f,"ORIGINAL","OLD");
            var originalMapper=c.getBean(DiningBatchQueryMapper.class);
            var mapper=spy(originalMapper);
            doAnswer(call -> {
                ListDiningBatchesQuery query=call.getArgument(2);
                long count=originalMapper.count("E","A",query);
                CompletableFuture.runAsync(() -> batch(f,"LATE","OLD")).orTimeout(5,TimeUnit.SECONDS).join();
                return count;
            }).when(mapper).count(anyString(),anyString(),any());
            var s=new DiningBatchQueryServiceImpl(mapper,c.getBean(StoreCardQueryPort.class),
                    c.getBean(PlatformTransactionManager.class));
            var result=s.list("E","A","staff",null);
            assertThat(result.total()).isOne();
            assertThat(result.records()).extracting(DiningBatchSummaryVO::batchNo).containsExactly("ORIGINAL");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_order_batch",Integer.class)).isEqualTo(2);
        });
    }
    @Test void invalidFiltersAndExcessiveDetailFanoutFailRatherThanReturningTruncatedSuccess() {
        run(c -> {
            var f=fixture(c); batch(f,"B1","OLD"); var s=c.getBean(DiningBatchQueryService.class);
            for(var query:List.of(new ListDiningBatchesQuery(0,20,null,null,null,null,null),
                    new ListDiningBatchesQuery(1,101,null,null,null,null,null),
                    new ListDiningBatchesQuery(1,20,null,null,"PAID",null,null),
                    new ListDiningBatchesQuery(1,20,null,null,null,null,"UNKNOWN")))
                DiningSessionTest.code(() -> s.list("E","A","staff",query),ErrorCode.BAD_REQUEST);
            var mapper=spy(c.getBean(DiningBatchQueryMapper.class));
            doReturn(Collections.nCopies(10001,new DiningBatchQueryMapper.MembershipRow("B1","OLD")))
                    .when(mapper).currentChecks(anyString(),anyString(),any());
            var bounded=new DiningBatchQueryServiceImpl(mapper,c.getBean(StoreCardQueryPort.class),
                    c.getBean(PlatformTransactionManager.class));
            DiningSessionTest.code(() -> bounded.list("E","A","staff",null),ErrorCode.CONFLICT);
        });
    }
    @Configuration(proxyBeanMethods=false) static class Queries {
        @Bean MapperFactoryBean<DiningBatchQueryMapper> batches(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var mapper=new MapperFactoryBean<>(DiningBatchQueryMapper.class); mapper.setSqlSessionTemplate(t); return mapper;
        }
        @Bean StoreCardQueryPort stores() {
            var card=new StoreCard("A","Store A","E",null,"READONLY",false,null,null,null,null,0,0);
            return numbers -> Map.of("A",card);
        }
        @Bean DiningBatchQueryService service(DiningBatchQueryMapper mapper,StoreCardQueryPort stores,
                PlatformTransactionManager manager) { return new DiningBatchQueryServiceImpl(mapper,stores,manager); }
    }
}
