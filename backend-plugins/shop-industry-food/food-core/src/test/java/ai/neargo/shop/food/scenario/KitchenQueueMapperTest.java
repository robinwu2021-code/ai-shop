package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Actual mapper JOIN and service reads; status/store/tenant filtering is executed by SQL, never mocked. */
class KitchenQueueMapperTest extends FoodWriteGuardFixture {
    @Test void defaultQueueContainsOnlyActiveWorkWhileExplicitBatchPreservesClosedHistory() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f=prepare(c); var sql=f.sql();
            create(sql,FnbOrderBatch.class,"fnb_order_batch"); create(sql,FnbKitchenTicket.class,"fnb_kitchen_ticket");
            create(sql,FnbKitchenTask.class,"fnb_kitchen_task"); create(sql,FnbOrderLine.class,"fnb_order_line");
            batch(sql,"OLD","MAIN","E","S","ACCEPTED","2026-10-20 10:00:00");
            batch(sql,"NEW","MAIN","E","S","ACCEPTED","2026-10-20 11:00:00");
            batch(sql,"HISTORY","MAIN","E","S","ACCEPTED","2026-10-19 10:00:00");
            batch(sql,"OTHER_STORE","MAIN","E","OTHER","ACCEPTED","2026-10-18 10:00:00");
            batch(sql,"OTHER_ENTITY","MAIN","FOREIGN","S","ACCEPTED","2026-10-18 10:00:00");
            batch(sql,"OTHER_TENANT","FOREIGN","E","S","ACCEPTED","2026-10-18 10:00:00");
            batch(sql,"UNACCEPTED","MAIN","E","S","BOUND","2026-10-18 10:00:00");
            ticket(sql,"ORIGINAL","OLD","MAIN","E","MAKE","CLOSED");
            task(sql,"SERVED","ORIGINAL","MAIN","E","HOT","SERVED");
            task(sql,"VOIDED","ORIGINAL","MAIN","E","HOT","VOIDED");
            task(sql,"CLOSED_ACTIVE","ORIGINAL","MAIN","E","HOT","READY");
            ticket(sql,"REMAKE","OLD","MAIN","E","REMAKE","ACTIVE");
            task(sql,"REMAKE_ACTIVE","REMAKE","MAIN","E","HOT","MAKING");
            ticket(sql,"FRESH","NEW","MAIN","E","MAKE","QUEUED");
            task(sql,"NEW_HOT","FRESH","MAIN","E","HOT","HELD");
            task(sql,"NEW_COLD","FRESH","MAIN","E","COLD","QUEUED");
            task(sql,"NEW_SERVED","FRESH","MAIN","E","HOT","SERVED");
            ticket(sql,"HIST","HISTORY","MAIN","E","MAKE","CLOSED");
            task(sql,"HIST_SERVED","HIST","MAIN","E","HOT","SERVED");
            for(String name:List.of("OTHER_STORE","OTHER_ENTITY","OTHER_TENANT","UNACCEPTED")) {
                String tenant=name.equals("OTHER_TENANT")?"FOREIGN":"MAIN";
                String entity=name.equals("OTHER_ENTITY")?"FOREIGN":"E";
                ticket(sql,name,name,tenant,entity,"MAKE","QUEUED");
                task(sql,name,name,tenant,entity,"HOT","QUEUED");
            }
            // Same ticket business number in another entity must not let a local orphan task join it.
            task(sql,"CROSS_JOIN","OTHER_ENTITY","MAIN","E","HOT","QUEUED");
            task(sql,"DELETED_TASK","FRESH","MAIN","E","HOT","QUEUED");
            sql.update("UPDATE fnb_kitchen_task SET deleted=1 WHERE task_no='DELETED_TASK'");
            var mapper=c.getBean(FoodMappers.KitchenTaskMapper.class);
            assertThat(DataScopeContext.executeWithoutScope(() -> mapper.activeBatchNos("E","S",null)))
                    .containsExactly("OLD","NEW");
            assertThat(DataScopeContext.executeWithoutScope(() -> mapper.activeBatchNos("E","S","COLD")))
                    .containsExactly("NEW");
            assertThat(DataScopeContext.executeWithoutScope(() -> mapper.activeBatchNos("E","MISSING",null))).isEmpty();
            var kitchen=new KitchenTaskServiceImpl(c.getBean(FoodMappers.OrderBatchMapper.class),
                    c.getBean(FoodMappers.OrderLineMapper.class),mock(FoodMappers.OrderComponentMapper.class),
                    mock(FoodMappers.ConfigRevisionMapper.class),c.getBean(FoodMappers.KitchenTicketMapper.class),mapper,
                    mock(FoodMappers.ServiceEventMapper.class),mock(FoodMappers.CourseMapper.class),mock(FoodMappers.CheckMapper.class),
                    mock(IdempotencyService.class),c.getBean(ObjectMapper.class),c.getBean(PlatformTransactionManager.class),
                    mock(OutboxPublisher.class));
            assertThat(kitchen.queue("E","S",null,null)).extracting(t -> t.taskNo())
                    .containsExactly("REMAKE_ACTIVE","NEW_COLD","NEW_HOT");
            assertThat(kitchen.queue("E","S","HOT",null)).extracting(t -> t.taskNo())
                    .containsExactly("REMAKE_ACTIVE","NEW_HOT");
            assertThat(kitchen.queue("E","S",null,"OLD")).extracting(t -> t.taskNo())
                    .containsExactlyInAnyOrder("SERVED","VOIDED","CLOSED_ACTIVE","REMAKE_ACTIVE");
            assertThat(kitchen.queue("E","S",null,"HISTORY")).extracting(t -> t.taskNo()).containsExactly("HIST_SERVED");
            assertThat(kitchen.queue("E","S",null,"OTHER_STORE")).isEmpty();
            assertThat(kitchen.queue("E","S",null,"OTHER_ENTITY")).isEmpty();
            assertThat(kitchen.queue("E","S",null,"OTHER_TENANT")).isEmpty();
        });
    }
    // Only persistence columns are derived; the fixture contains no replica of the production JOIN/filter.
    private void create(JdbcTemplate sql,Class<?> entity,String table) {
        var columns=new ArrayList<String>();
        for(Class<?> type=entity; type!=Object.class; type=type.getSuperclass()) for(var field:type.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers())) continue;
            String name=field.getName().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(java.util.Locale.ROOT);
            Class<?> value=field.getType();
            String data=name.equals("id")?"BIGINT AUTO_INCREMENT PRIMARY KEY":value==LocalDateTime.class?"TIMESTAMP":
                    value==LocalDate.class?"DATE":value==BigDecimal.class?"DECIMAL(24,6)":
                    value==Long.class||value==Integer.class?"BIGINT":"VARCHAR(255)";
            columns.add(name+" "+data);
        }
        sql.execute("CREATE TABLE "+table+" ("+String.join(",",columns)+")");
    }
    private void batch(JdbcTemplate sql,String number,String tenant,String entity,String store,String status,String at) {
        sql.update("INSERT INTO fnb_order_batch(tenant_no,entity_no,store_no,batch_no,status,accepted_at,deleted,version) VALUES(?,?,?,?,?,?,0,0)",
                tenant,entity,store,number,status,LocalDateTime.parse(at.replace(' ','T')));
    }
    private void ticket(JdbcTemplate sql,String number,String batch,String tenant,String entity,String kind,String status) {
        sql.update("INSERT INTO fnb_kitchen_ticket(tenant_no,entity_no,ticket_no,batch_no,kind,status,deleted,version) VALUES(?,?,?,?,?,?,0,0)",
                tenant,entity,number,batch,kind,status);
    }
    private void task(JdbcTemplate sql,String number,String ticket,String tenant,String entity,String station,String status) {
        sql.update("INSERT INTO fnb_order_line(tenant_no,entity_no,food_line_no,deleted,version) VALUES(?,?,?,0,0)",tenant,entity,number);
        sql.update("INSERT INTO fnb_kitchen_task(tenant_no,entity_no,task_no,ticket_no,food_line_no,station_no,status,task_role,"
                +"route_version,qty,making_qty,ready_qty,served_qty,void_qty,created_at,deleted,version) "
                +"VALUES(?,?,?,?,?,?,?,'MAKE',1,1,0,0,0,0,'2026-10-20 12:00:00',0,0)",tenant,entity,number,ticket,number,station,status);
    }
    private static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionTemplate template) {
        var bean=new MapperFactoryBean<>(type);bean.setSqlSessionTemplate(template);return bean;
    }
    @Configuration(proxyBeanMethods=false) static class Mappers {
        @Bean MapperFactoryBean<FoodMappers.KitchenTaskMapper> kitchenTaskMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.KitchenTaskMapper.class,t); }
        @Bean MapperFactoryBean<FoodMappers.KitchenTicketMapper> kitchenTicketMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.KitchenTicketMapper.class,t); }
        @Bean MapperFactoryBean<FoodMappers.OrderBatchMapper> orderBatchMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.OrderBatchMapper.class,t); }
        @Bean MapperFactoryBean<FoodMappers.OrderLineMapper> orderLineMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.OrderLineMapper.class,t); }
    }
}
