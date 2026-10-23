package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbProductionConsumption;
import ai.neargo.shop.food.entity.FnbProductionConsumptionLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real production mapper SQL + service pagination on H2, or disposable native MySQL when enabled. */
class ProductionConsumptionListMapperTest extends FoodWriteGuardFixture {
    @Test void actualSqlPagesScopeAndExclusiveDatesWithRealTotalAndUnknownFacts() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var sql=prepare(c).sql(); tables(sql);
            head(sql,"C1","MAIN","E","S","2026-10-20",0);
            head(sql,"C2","MAIN","E","S","2026-10-21",0);
            head(sql,"C3","MAIN","E","S","2026-10-22",0);
            head(sql,"BEFORE","MAIN","E","S","2026-10-19",0);
            head(sql,"TO","MAIN","E","S","2026-10-23",0);
            head(sql,"FOREIGN_TENANT","OTHER","E","S","2026-10-21",0);
            head(sql,"FOREIGN_ENTITY","MAIN","OTHER","S","2026-10-21",0);
            head(sql,"FOREIGN_STORE","MAIN","E","OTHER","2026-10-21",0);
            head(sql,"DELETED","MAIN","E","S","2026-10-21",1);
            // The same consumption business key elsewhere must not leak projected lines into records.
            material(sql,"C2","OTHER","E","FOREIGN",0,2);
            material(sql,"C2","MAIN","OTHER","FOREIGN",0,3);
            material(sql,"C2","MAIN","E","DELETED",1,4);
            var inventory=mock(InventoryProductionPort.class);
            var stores=stores();
            var service=service(c,stores,inventory);
            var result=service.list("E","S","actor",query(2,1,null,null,null));
            assertThat(result.total()).isEqualTo(3); assertThat(result.page()).isEqualTo(2); assertThat(result.size()).isEqualTo(1);
            assertThat(result.records()).extracting(v -> v.consumptionNo()).containsExactly("C2");
            var row=result.records().getFirst();
            assertThat(row.quantityEvidence()).isEqualTo("THEORETICAL_ONLY");
            assertThat(row.totalCostMinor()).isNull(); assertThat(row.costCurrency()).isNull();
            assertThat(row.lines()).hasSize(1);
            assertThat(row.lines().getFirst().inventoryIssuedQty()).isNull();
            assertThat(row.lines().getFirst().costMinor()).isNull();
            assertThat(service.list("E","S","actor",query(9,1,null,null,null)).records()).isEmpty();
            assertThat(service.list("E","S","actor",query(9,1,null,null,null)).total()).isEqualTo(3);
            verifyNoInteractions(inventory);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_production_consumption WHERE status='BLOCKED'",Long.class))
                    .isEqualTo(9);
        });
    }

    @Test void actualExistsFiltersFollowOriginalSkuAndPersistedMaterialWithoutDuplicateOrCrossScopeMatches() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var sql=prepare(c).sql(); tables(sql);
            for(String no:List.of("C1","C2","C3","CROSS","DELETED_EVENT","DELETED_TASK","DELETED_LINE","DELETED_BATCH","BAD_STORE","BAD_TENANT")) {
                head(sql,no,"MAIN","E","S","2026-10-21",0);
                source(sql,no,no.equals("BAD_TENANT")?"OTHER":"MAIN","E",
                        no.equals("BAD_STORE")?"OTHER":"S",no.equals("C3")?"SKU_B":"SKU_A");
            }
            material(sql,"C1","MAIN","E","MAT",0,2); // Two matching materials still yield one head.
            sql.update("UPDATE fnb_service_event SET entity_no='OTHER' WHERE event_no='EV-CROSS'");
            sql.update("UPDATE fnb_service_event SET deleted=1 WHERE event_no='EV-DELETED_EVENT'");
            sql.update("UPDATE fnb_kitchen_task SET deleted=1 WHERE task_no='T-DELETED_TASK'");
            sql.update("UPDATE fnb_order_line SET deleted=1 WHERE food_line_no='L-DELETED_LINE'");
            sql.update("UPDATE fnb_order_batch SET deleted=1 WHERE batch_no='B-DELETED_BATCH'");
            material(sql,"C2","OTHER","E","BORROWED",0,2);
            material(sql,"C2","MAIN","E","BORROWED",1,3);
            var inventory=mock(InventoryProductionPort.class); var service=service(c,stores(),inventory);
            var result=service.list("E","S","actor",query(1,1,"SKU_A","MAT","BLOCKED"));
            assertThat(result.total()).isEqualTo(2); assertThat(result.records()).hasSize(1);
            assertThat(result.records().getFirst().consumptionNo()).isEqualTo("C2");
            assertThat(service.list("E","S","actor",query(2,1,"SKU_A","MAT","BLOCKED")).records())
                    .extracting(v -> v.consumptionNo()).containsExactly("C1");
            assertThat(service.list("E","S","actor",query(1,20,"SKU_A","BORROWED",null)).total()).isZero();
            assertThat(service.list("E","S","actor",query(1,20,"SKU_A",null,"CONFIRMED")).total()).isZero();
            assertThat(service.list("E","S","actor",query(1,20,"SKU_B",null,null)).records())
                    .extracting(v -> v.consumptionNo()).containsExactly("C3");
            verifyNoInteractions(inventory);
        });
    }

    @Test void invalidBoundsAndForeignDirectoryOwnershipFailBeforeAnyProjectionRead() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            prepare(c); // No business tables exist: invalid/auth paths must not attempt SQL at all.
            var stores=stores(); var inventory=mock(InventoryProductionPort.class); var service=service(c,stores,inventory);
            for(var q:List.of(query(0,20,null,null,null),query(1,101,null,null,null),query(1,0,null,null,null),
                    query(1,20,null,null,"INVENTED"),query(1,20," ",null,null),
                    new ListQuery(1,20,LocalDate.of(2026,1,1),LocalDate.of(2026,5,1),null,null,null),
                    new ListQuery(1,20,LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),null,null,null)))
                assertThatThrownBy(() -> service.list("E","S","actor",q)).isInstanceOf(BizException.class);
            when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","store","FOREIGN",null,
                    "ACTIVE",true,null,null,null,null,0,0)));
            assertThatThrownBy(() -> service.list("E","S","actor",query(1,20,null,null,null)))
                    .isInstanceOf(BizException.class);
            verifyNoInteractions(inventory);
        });
    }
    private static ListQuery query(int page,int size,String sku,String item,String status) {
        return new ListQuery(page,size,LocalDate.of(2026,10,20),LocalDate.of(2026,10,23),sku,item,status);
    }
    private StoreCardQueryPort stores() {
        var port=mock(StoreCardQueryPort.class);
        when(port.cards(any())).thenReturn(Map.of("S",new StoreCard("S","store","E",null,"ACTIVE",true,null,null,null,null,0,0)));
        return port;
    }
    private ProductionConsumptionServiceImpl service(org.springframework.context.ApplicationContext c,
            StoreCardQueryPort stores,InventoryProductionPort inventory) {
        return new ProductionConsumptionServiceImpl(c.getBean(FoodMappers.ProductionConsumptionMapper.class),
                c.getBean(FoodMappers.ProductionConsumptionLineMapper.class),mock(FoodMappers.OrderBatchMapper.class),
                mock(FoodMappers.OrderLineMapper.class),mock(FoodMappers.KitchenTicketMapper.class),
                mock(FoodMappers.KitchenTaskMapper.class),mock(FoodMappers.ServiceEventMapper.class),
                mock(FoodMappers.ConfigRevisionMapper.class),stores,inventory,c.getBean(ObjectMapper.class),
                c.getBean(PlatformTransactionManager.class));
    }
    private void tables(JdbcTemplate sql) {
        create(sql,FnbProductionConsumption.class,"fnb_production_consumption");
        create(sql,FnbProductionConsumptionLine.class,"fnb_production_consumption_line");
        create(sql,FnbServiceEvent.class,"fnb_service_event"); create(sql,FnbKitchenTask.class,"fnb_kitchen_task");
        create(sql,FnbOrderLine.class,"fnb_order_line"); create(sql,FnbOrderBatch.class,"fnb_order_batch");
    }
    // Only real entity column shapes are derived; production predicates/pagination are never duplicated in a test query.
    private void create(JdbcTemplate sql,Class<?> entity,String table) {
        var columns=new ArrayList<String>();
        for(Class<?> type=entity;type!=Object.class;type=type.getSuperclass()) for(var field:type.getDeclaredFields()) {
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
    private void head(JdbcTemplate sql,String no,String tenant,String entity,String store,String date,int deleted) {
        sql.update("INSERT INTO fnb_production_consumption(tenant_no,entity_no,store_no,consumption_no,source_event_no,"
                + "purpose,status,request_hash,business_date,occurred_at,version,deleted) "
                + "VALUES(?,?,?,?,?,?,'BLOCKED','hash',?,?,0,?)",tenant,entity,store,no,"EV-"+no,"PRODUCTION",
                LocalDate.parse(date),LocalDate.parse(date).atTime(12,0),deleted);
        material(sql,no,tenant,entity,"MAT",0,1);
    }
    private void material(JdbcTemplate sql,String no,String tenant,String entity,String item,int deleted,int line) {
        sql.update("INSERT INTO fnb_production_consumption_line(tenant_no,entity_no,consumption_no,line_no,component_path,"
                + "item_ref,theory_qty,unit,recipe_revision_no,status,version,deleted) VALUES(?,?,?,?,'original',?,2,'UNIT','REV','PENDING',0,?)",
                tenant,entity,no,line,item,deleted);
    }
    private void source(JdbcTemplate sql,String no,String tenant,String entity,String store,String sku) {
        sql.update("INSERT INTO fnb_order_batch(tenant_no,entity_no,store_no,batch_no,version,deleted) VALUES(?,?,?,?,0,0)",tenant,entity,store,"B-"+no);
        sql.update("INSERT INTO fnb_order_line(tenant_no,entity_no,batch_no,food_line_no,sku_no,version,deleted) VALUES(?,?,?,?,?,0,0)",tenant,entity,"B-"+no,"L-"+no,sku);
        sql.update("INSERT INTO fnb_kitchen_task(tenant_no,entity_no,task_no,food_line_no,version,deleted) VALUES(?,?,?,?,0,0)",tenant,entity,"T-"+no,"L-"+no);
        sql.update("INSERT INTO fnb_service_event(tenant_no,entity_no,event_no,task_no,version,deleted) VALUES(?,?,?,?,0,0)",tenant,entity,"EV-"+no,"T-"+no);
    }
    private static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionTemplate template) {
        var bean=new MapperFactoryBean<>(type); bean.setSqlSessionTemplate(template); return bean;
    }
    @Configuration(proxyBeanMethods=false) static class Mappers {
        @Bean MapperFactoryBean<FoodMappers.ProductionConsumptionMapper> productionConsumptionMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.ProductionConsumptionMapper.class,t); }
        @Bean MapperFactoryBean<FoodMappers.ProductionConsumptionLineMapper> productionConsumptionLineMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(FoodMappers.ProductionConsumptionLineMapper.class,t); }
    }
}
