package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.QueueTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.QueueServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DiningQueueChanged;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Native owner + queue + idempotency + audit + outbox rollback against the disposable primary MySQL. */
@EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
class DiningQueuePrimaryDatabaseTest extends FoodWriteGuardFixture {
    @Test void tableConflictRollsBackOriginalKeyAndCanRetryAnotherTable() {
        run((c,f,checks,queue,helper,fail) -> {
            var occupied=helper.table(c,"TAKEN"); var available=helper.table(c,"FREE");
            checks.openCheck("E","A","actor",helper.open(occupied,2,"EXCLUSIVE"),"other-party");
            var stale=helper.open(occupied,2,"EXCLUSIVE");
            assertThatThrownBy(() -> queue.seat("E","A","actor","Q",new SeatQueueReq(0L,stale)))
                    .isInstanceOf(BizException.class);
            assertThat(queue.get("E","A","actor","Q").status()).isEqualTo("CALLED");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent WHERE idem_key='QUEUE_SEAT:Q'",Integer.class)).isZero();
            var result=queue.seat("E","A","actor","Q",new SeatQueueReq(0L,helper.open(available,2,"EXCLUSIVE")));
            assertThat(result.status()).isEqualTo("SEATED");
            assertThat(checks.getCheck("E","A",result.checkNo()).check().tableNos()).containsExactly(available.tableNo());
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log WHERE object_type='QUEUE' AND action='QUEUE_SEATING'",Integer.class)).isZero();
        });
    }
    @Test void failureAfterBothOwnerEventsRollsBackEveryFactAndOriginalKey() {
        run((c,f,checks,queue,helper,fail) -> {
            var table=helper.table(c,"T1");
            var before=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(String name:List.of("fnb_queue_ticket","fnb_check","fnb_table","fnb_table_occupancy","fnb_seat",
                    "fnb_action_log","sys_idempotent","sys_outbox"))
                before.put(name,f.sql().queryForList("SELECT * FROM "+name+" ORDER BY id"));
            fail.set(true);
            var request=new SeatQueueReq(0L,helper.open(table,2,"EXCLUSIVE"));
            assertThatThrownBy(() -> queue.seat("E","A","actor","Q",request))
                    .isInstanceOf(IllegalStateException.class).hasMessage("after-queue-event-flush");
            for(var entry:before.entrySet()) assertThat(f.sql().queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY id"))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
            fail.set(false);
            var seated=queue.seat("E","A","actor","Q",request);
            assertThat(queue.seat("E","A","actor","Q",request).checkNo()).isEqualTo(seated.checkNo());
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log WHERE action IN ('OPEN_CHECK','QUEUE_SEATED')",Integer.class)).isEqualTo(2);
        });
    }
    @Test void onlyExplicitQueuedCommandCanJoinPrimaryTransaction() {
        run((c,f,checks,queue,helper,fail) -> {
            var table=helper.table(c,"T1"); var request=helper.open(table,2,"EXCLUSIVE");
            assertThatThrownBy(() -> checks.openQueuedCheck("E","A","actor",request,"direct"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.tx().execute(status -> checks.openCheck("E","A","actor",request,"joined")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isZero();
            assertThat(queue.seat("E","A","actor","Q",new SeatQueueReq(0L,request)).status()).isEqualTo("SEATED");
        });
    }
    private void run(Case test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class,
                DiningSessionTest.Sessions.class,CheckServiceTest.Checks.class,QueueMapper.class).run(c -> {
            var helper=new CheckServiceTest(); var f=helper.fixture(c);
            new ResourceDatabasePopulator(new ClassPathResource("db/industry/food/V10__queue_ticket.sql")).execute(f.source());
            f.sql().update("INSERT INTO fnb_queue_ticket(entity_no,created_at,created_by,updated_at,updated_by,ticket_no,"
                    +"store_no,business_date,party_size,status,request_key,request_hash) "
                    +"VALUES('E',NOW(),'actor',NOW(),'actor','Q','A','2026-10-24',2,'CALLED',?,?)","1".repeat(64),"2".repeat(64));
            var fail=new AtomicBoolean(); var actual=c.getBean("foodOutboxPublisher",OutboxPublisher.class);
            OutboxPublisher publisher=(event,options) -> {
                var result=actual.publish(event,options);
                if(fail.get() && event instanceof DiningQueueChanged) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_check",Integer.class)).isOne();
                            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_type IN "
                                    +"('FOOD_CHECK_CHANGED','DINING_QUEUE_CHANGED')",Integer.class)).isEqualTo(2);
                            throw new IllegalStateException("after-queue-event-flush");
                        }
                    });
                }
                return result;
            };
            StoreCardQueryPort stores=numbers -> Map.of("A",new StoreCard("A","Store","E",null,"ACTIVE",true,null,null,null,null,0,0));
            var checks=c.getBean(CheckService.class);
            var queue=new QueueServiceImpl(c.getBean(QueueTicketMapper.class),c.getBean(StorePolicyMapper.class),
                    c.getBean(ActionLogMapper.class),c.getBean(StoreOperationService.class),checks,stores,
                    c.getBean(IdempotencyService.class),publisher,c.getBean(ObjectMapper.class),c.getBean(PlatformTransactionManager.class));
            test.run(c,f,checks,queue,helper,fail);
        });
    }
    private interface Case {
        void run(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,Fixture fixture,
                CheckService checks,QueueServiceImpl queue,CheckServiceTest helper,AtomicBoolean fail);
    }
    @Configuration(proxyBeanMethods=false) static class QueueMapper {
        @Bean MapperFactoryBean<QueueTicketMapper> queueTicketMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var mapper=new MapperFactoryBean<>(QueueTicketMapper.class);mapper.setSqlSessionTemplate(template);return mapper;
        }
    }
}
