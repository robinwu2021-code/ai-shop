package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SeatReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.food.spi.TableInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodServerPrimaryDatabaseTest {
    @Test void actualStandaloneServerReusesPrimaryAssemblyAndPlatformAudit() {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_server_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        try {
            String url = adminUrl.replace("/mysql", "/" + database);
            var source = new DriverManagerDataSource(url, "root", "");
            var fixture = Path.of(System.getProperty("food.reactor.root"), "food-core/src/test/resources/food-primary-fixture.sql");
            new ResourceDatabasePopulator(new FileSystemResource(fixture)).execute(source);
            Flyway.configure().dataSource(source).locations("classpath:db/industry/food").table("fnb_flyway_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(FoodApplication.class)
                    .withPropertyValues("shop.outbox.sources.platform.mode=unified",
                            "shop.outbox.sources.inventory.mode=legacy", "shop.outbox.enabled=false",
                            "spring.datasource.url=" + url, "spring.datasource.username=root", "spring.datasource.password=",
                            "shop.services.internal-token=food-disposable-server-test", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY,
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration")
                    .run(context -> {
                        FoodServerProducerAssertions.verify(context);
                        assertThat(context).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(SqlSessionFactory.class)
                                .hasSingleBean(PlatformTransactionManager.class).hasSingleBean(MetaObjectHandler.class)
                                .hasSingleBean(FoodMappers.TableAreaMapper.class).doesNotHaveBean(Flyway.class);
                        assertThat(context.getBean(MetaObjectHandler.class)).isInstanceOf(AuditMetaObjectHandler.class);
                        assertThat(context.getBean(FoodSchemaReady.class).ready()).isTrue();
                        assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
                        var sql = new JdbcTemplate(context.getBean(DataSource.class));
                        sql.update("INSERT INTO fnb_store_policy(tenant_no,entity_no,policy_no,store_no,timezone,"
                                + "cutover_minute,effective_from,pay_policy,capabilities,settings,created_at,created_by,updated_at,updated_by) VALUES "
                                + "('MAIN','E','P1','A','Asia/Shanghai',240,'2026-10-01 00:00:00',"
                                + "'POSTPAY','[\"TABLE_CONFIG\"]','{}',NOW(3),'SYSTEM',NOW(3),'SYSTEM')");
                        var mvc = MockMvcBuilders.webAppContextSetup(context)
                                .addFilters(context.getBean(FilterChainProxy.class)).build();
                        String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"at\":\"2026-10-20T12:00:00Z\"}";
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH).contentType(MediaType.APPLICATION_JSON)
                                        .content(body)).andExpect(status().isUnauthorized());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.policyNo").value("P1"))
                                .andExpect(jsonPath("$.payPolicy").value("POSTPAY"))
                                .andExpect(jsonPath("$.data").doesNotExist());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"E\"","\"FOREIGN\"")))
                                .andExpect(status().isNotFound());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeNo\":\"A\"}"))
                                .andExpect(status().isBadRequest());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_store_policy",Integer.class)).isEqualTo(1);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isZero();
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isZero();
                        // Keep this publication in the future; fixed calendar dates eventually become current.
                        String futurePolicy = Instant.now().plus(2, ChronoUnit.DAYS)
                                .truncatedTo(ChronoUnit.MILLIS).toString();
                        String command = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"trusted-actor\","
                                + "\"idempotencyKey\":\"original-publication\","
                                + "\"policy\":{\"timezone\":\"Asia/Shanghai\","
                                + "\"cutoverMinute\":240,\"effectiveFrom\":\"" + futurePolicy + "\","
                                + "\"payPolicy\":\"POSTPAY\",\"capabilities\":[],\"version\":0}}";
                        String receipt = mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(command))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                                .andExpect(jsonPath("$.version").value(1))
                                .andReturn().getResponse().getContentAsString();
                        mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(command))
                                .andExpect(status().isOk()).andExpect(result -> assertThat(
                                        result.getResponse().getContentAsString()).isEqualTo(receipt));
                        mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(command.replace(futurePolicy,
                                                Instant.parse(futurePolicy).plus(1, ChronoUnit.DAYS).toString())))
                                .andExpect(status().isConflict());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_store_policy",Integer.class))
                                .isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_action_log",Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT operator_no FROM fnb_action_log",String.class))
                                .isEqualTo("trusted-actor");
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isOne();
                        String calendar = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"trusted-actor\","
                                + "\"localDate\":\"2026-10-20\",\"idempotencyKey\":\"calendar-create\","
                                + "\"calendar\":{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}}";
                        for (int retry=0; retry<2; retry++) {
                            mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                            .contentType(MediaType.APPLICATION_JSON).content(calendar))
                                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
                        }
                        String update = calendar.replace("calendar-create", "calendar-update")
                                .replace("\"CLOSED\",\"intervals\":[]", "\"OPEN\",\"intervals\":[\"22:00-02:00\"]")
                                .replace("\"version\":0", "\"version\":1");
                        mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(update))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
                        mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(calendar.replace("calendar-create", "calendar-stale")))
                                .andExpect(status().isConflict());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_service_calendar",Integer.class))
                                .isOne();
                        assertThat(sql.queryForObject("SELECT version FROM fnb_service_calendar",Long.class))
                                .isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT policy_no FROM fnb_service_calendar",String.class))
                                .isEqualTo("P1");
                        for (String table:List.of("fnb_action_log","sys_idempotent","sys_outbox")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(3);
                        }
                        String createArea = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"trusted-actor\","
                                + "\"idempotencyKey\":\"create-area\","
                                + "\"area\":{\"code\":\"HALL\",\"name\":\"大厅\",\"sortNo\":0}}";
                        String areaReceipt = mvc.perform(post(TableInternalApi.CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(createArea))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.area.version").value(0))
                                .andReturn().getResponse().getContentAsString();
                        mvc.perform(post(TableInternalApi.CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(createArea))
                                .andExpect(status().isOk()).andExpect(r -> assertThat(
                                        r.getResponse().getContentAsString()).isEqualTo(areaReceipt));
                        String areaNo = context.getBean(ObjectMapper.class).readTree(areaReceipt)
                                .get("area").get("areaNo").asString();
                        mvc.perform(post(TableInternalApi.LIST_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"entityNo\":\"E\",\"storeNo\":\"A\"}"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
                        String editArea = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"areaNo\":\""+areaNo+"\","
                                + "\"actorNo\":\"trusted-actor\",\"idempotencyKey\":\"edit-area\","
                                + "\"area\":{\"name\":\"包间\",\"sortNo\":1,\"version\":0}}";
                        mvc.perform(post(TableInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(editArea))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.area.version").value(1));
                        mvc.perform(post(TableInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(editArea.replace("edit-area","foreign-area").replace("\"E\"","\"OTHER\"")))
                                .andExpect(status().isNotFound());
                        mvc.perform(post(TableInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(editArea.replace("edit-area","stale-area")))
                                .andExpect(status().isConflict());
                        String disableArea = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"areaNo\":\""+areaNo+"\","
                                + "\"actorNo\":\"trusted-actor\",\"idempotencyKey\":\"disable-area\","
                                + "\"area\":{\"status\":\"DISABLED\",\"reason\":\"停用\",\"version\":1}}";
                        mvc.perform(post(TableInternalApi.STATUS_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON).content(disableArea))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.area.version").value(2))
                                .andExpect(jsonPath("$.area.status").value("DISABLED"));
                        mvc.perform(post(TableInternalApi.STATUS_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(disableArea.replace("disable-area","no-change-area").replace("\"version\":1","\"version\":2")))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.area.version").value(2));
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isEqualTo(6);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(6);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(7);
                        assertThat(sql.queryForObject("SELECT reason FROM fnb_action_log WHERE action='SET_STATUS_AREA'",
                                String.class)).isEqualTo("停用");
                        var tableService=context.getBean(TableService.class);
                        tableService.setStatusTableArea("E","A",areaNo,"trusted-actor",
                                new SetStatusTableAreaReq("ACTIVE",2L,"桌台配置"),"enable-area-for-table");
                        var json=context.getBean(ObjectMapper.class);
                        var createTable=new TableInternalApi.TableCreateCommand("E","A","trusted-actor","table-create",
                                new CreateTableReq(areaNo,"T01","四人桌",4,0,null));
                        String tableReceipt=tableCall(mvc,TableInternalApi.TABLE_CREATE_PATH,createTable,json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.table.version").value(0))
                                .andExpect(jsonPath("$.table.turnoverState").value("IDLE"))
                                .andReturn().getResponse().getContentAsString();
                        tableCall(mvc,TableInternalApi.TABLE_CREATE_PATH,createTable,json).andExpect(status().isOk())
                                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(tableReceipt));
                        String tableNo=json.readTree(tableReceipt).get("table").get("tableNo").asString();
                        tableCall(mvc,TableInternalApi.TABLE_LIST_PATH,new TableInternalApi.TableLookup("E","A",null),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
                        tableCall(mvc,TableInternalApi.TABLE_GET_PATH,new TableInternalApi.TableIdentity("E","A",tableNo),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.table.capacity").value(4));
                        tableCall(mvc,TableInternalApi.TABLE_SAVE_PATH,new TableInternalApi.TableSaveCommand("E","A",tableNo,
                                "trusted-actor","table-save",new SaveTableReq(areaNo,"六人桌",6,1,0L)),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.table.version").value(1));
                        tableCall(mvc,TableInternalApi.TABLE_STATUS_PATH,new TableInternalApi.TableStatusCommand("E","A",tableNo,
                                "trusted-actor","table-off",new SetStatusTableReq("DISABLED",1L,"停用")),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.table.version").value(2));
                        var archive=new TableInternalApi.TableArchiveCommand("E","A",tableNo,"trusted-actor","table-archive",
                                new ArchiveTableReq(2L,"归档"));
                        String archiveReceipt=tableCall(mvc,TableInternalApi.TABLE_ARCHIVE_PATH,archive,json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.version").value(3))
                                .andReturn().getResponse().getContentAsString();
                        tableCall(mvc,TableInternalApi.TABLE_ARCHIVE_PATH,archive,json).andExpect(status().isOk())
                                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(archiveReceipt));
                        tableCall(mvc,TableInternalApi.TABLE_GET_PATH,new TableInternalApi.TableIdentity("E","A",tableNo),json)
                                .andExpect(status().isNotFound());
                        tableCall(mvc,TableInternalApi.TABLE_CREATE_PATH,new TableInternalApi.TableCreateCommand("E","A",
                                "trusted-actor","reuse-code",new CreateTableReq(areaNo,"T01","重建",4,0,null)),json)
                                .andExpect(status().isConflict());
                        assertThat(sql.queryForObject("SELECT deleted FROM fnb_table",Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT version FROM fnb_table",Long.class)).isEqualTo(3);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_action_log",Integer.class)).isEqualTo(11);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(11);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isEqualTo(12);
                        String diningTable=json.readTree(tableCall(mvc,TableInternalApi.TABLE_CREATE_PATH,
                                new TableInternalApi.TableCreateCommand("E","A","trusted-actor","dining-table",
                                        new CreateTableReq(areaNo,"T02","扫码桌",4,0,null)),json)
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                                .get("table").get("tableNo").asString();
                        var rotate=new DiningSessionInternalApi.RotateCommand("E","A",diningTable,"trusted-actor",
                                "rotate",new RotateTableTokenReq(Instant.now().plusSeconds(3600),0L));
                        String raw=json.readTree(tableCall(mvc,DiningSessionInternalApi.ROTATE_PATH,rotate,json)
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asString();
                        tableCall(mvc,DiningSessionInternalApi.ROTATE_PATH,rotate,json).andExpect(status().isConflict());
                        tableCall(mvc,DiningSessionInternalApi.RESOLVE_PATH,new DiningSessionInternalApi.ResolveCommand(raw),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.storeNo").value("A"));
                        var started=json.readTree(tableCall(mvc,DiningSessionInternalApi.START_PATH,
                                new DiningSessionInternalApi.StartCommand("E","A",null,"start",
                                        new StartDiningSessionReq(raw,null)),json).andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString());
                        var end=new DiningSessionInternalApi.EndCommand(started.get("sessionNo").asString(),
                                started.get("accessToken").asString(),null,"end");
                        tableCall(mvc,DiningSessionInternalApi.END_PATH,end,json).andExpect(status().isOk());
                        tableCall(mvc,DiningSessionInternalApi.END_PATH,end,json).andExpect(status().isOk());
                        tableCall(mvc,DiningSessionInternalApi.REVOKE_PATH,new DiningSessionInternalApi.RevokeCommand(
                                "E","A",diningTable,"trusted-actor","revoke",new RevokeTableTokenReq(1L,"撤销")),json)
                                .andExpect(status().isOk());
                        tableCall(mvc,DiningSessionInternalApi.RESOLVE_PATH,new DiningSessionInternalApi.ResolveCommand(raw),json)
                                .andExpect(status().isNotFound());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_guest_session WHERE revoked_at IS NULL",Integer.class)).isZero();
                        assertThat(context.getBean(FoodSchemaReady.class).version()).isEqualTo("25");
                        var extraTables=new ArrayList<String>();
                        for(String code:List.of("T03","T04")) extraTables.add(json.readTree(tableCall(mvc,
                                TableInternalApi.TABLE_CREATE_PATH,new TableInternalApi.TableCreateCommand("E","A",
                                        "trusted-actor","check-table-"+code,new CreateTableReq(areaNo,code,code,4,0,null)),json)
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                                .get("table").get("tableNo").asString());
                        // The following legacy empty-check seat/clear flow requires PREPAY.
                        sql.update("UPDATE fnb_store_policy SET pay_policy='PREPAY' WHERE policy_no='P1' AND entity_no='E' AND store_no='A'");
                        var checkOpen=new CheckInternalApi.OpenCommand("E","A","trusted-actor","check-open",
                                new OpenCheckReq(List.of(diningTable),2,"PREPAY",null,
                                        List.of(new TableVersionReq(diningTable,2L)),"SHARED",List.of(new SeatReq(null,"1",null)),null));
                        String openedBody=tableCall(mvc,CheckInternalApi.OPEN_PATH,checkOpen,json).andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString();
                        String checkNo=json.readTree(openedBody).get("check").get("checkNo").asString();
                        tableCall(mvc,CheckInternalApi.OPEN_PATH,checkOpen,json).andExpect(status().isOk())
                                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(openedBody));
                        tableCall(mvc,CheckInternalApi.SHARE_PATH,new CheckInternalApi.ShareCommand("E","A",diningTable,
                                "trusted-actor","check-share",new OpenSharedCheckReq(3L,1,"PREPAY")),json).andExpect(status().isOk());
                        tableCall(mvc,CheckInternalApi.LIST_PATH,new CheckInternalApi.Lookup("E","A",null),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
                        tableCall(mvc,CheckInternalApi.SEATS_PATH,new CheckInternalApi.SeatsCommand("E","A",checkNo,
                                "trusted-actor","check-seats",new SaveCheckSeatsReq(0L,3,List.of(new SeatReq(null,"1",null)),"增加人数")),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.check.partySize").value(3));
                        var moveCommand=new CheckInternalApi.TransferCommand("E","A",checkNo,"trusted-actor","check-move",
                                new TransferCheckReq(1L,"换台",null,List.of(extraTables.get(0)),
                                        List.of(new TableVersionReq(diningTable,5L),new TableVersionReq(extraTables.get(0),0L))));
                        String movedBody=tableCall(mvc,CheckInternalApi.TRANSFER_PATH,moveCommand,json).andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString();
                        tableCall(mvc,CheckInternalApi.TRANSFER_PATH,moveCommand,json).andExpect(status().isOk())
                                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(movedBody));
                        tableCall(mvc,CheckInternalApi.COMBINE_PATH,new CheckInternalApi.CombineCommand("E","A",checkNo,
                                "trusted-actor","check-combine",new CombineTablesReq(2L,
                                        List.of(new TableVersionReq(extraTables.get(1),0L)),"增加桌台")),json).andExpect(status().isOk());
                        var detail=json.readTree(tableCall(mvc,CheckInternalApi.GET_PATH,new CheckInternalApi.Identity("E","A",checkNo),json)
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                        var occupancyNos=new ArrayList<String>();
                        detail.get("occupancies").forEach(node -> occupancyNos.add(node.get("occupancyNo").asString()));
                        assertThat(occupancyNos).hasSize(2);
                        var clear=new CheckInternalApi.ClearCommand("E","A",checkNo,"trusted-actor","check-clear",
                                new ClearCheckReq(3L,"已清洁",null,occupancyNos));
                        tableCall(mvc,CheckInternalApi.CLEAR_PATH,clear,json).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
                        tableCall(mvc,CheckInternalApi.CLEAR_PATH,clear,json).andExpect(status().isOk());
                        String movedOperation=json.readTree(movedBody).get("operation").get("operationNo").asString();
                        tableCall(mvc,CheckInternalApi.OPERATION_PATH,new CheckInternalApi.OperationLookup("E","A",movedOperation),json)
                                .andExpect(status().isOk()).andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(movedBody));
                        tableCall(mvc,CheckInternalApi.ACTIONS_PATH,new CheckInternalApi.ActionsLookup("E","A",checkNo,null),json)
                                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(5));
                        tableCall(mvc,CheckInternalApi.GET_PATH,new CheckInternalApi.Identity("E","OTHER",checkNo),json)
                                .andExpect(status().isNotFound());
                        for(String target:extraTables) assertThat(sql.queryForObject("SELECT turnover_state FROM fnb_table WHERE table_no=?",String.class,target)).isEqualTo("IDLE");
                        sql.update("INSERT INTO fnb_reservation(entity_no,created_at,created_by,updated_at,updated_by,"
                                +"reservation_no,store_no,contact_ref,party_size,start_at,end_at,status) VALUES"
                                +"('E',NOW(3),'SYSTEM',NOW(3),'SYSTEM','RES1','A','protected-contact',2,"
                                +"'2026-10-21 11:00:00','2026-10-21 13:00:00','CONFIRMED')");
                        String reservations=tableCall(mvc,ReservationInternalApi.LIST_PATH,new ReservationInternalApi.Lookup("E","A",
                                new ListReservationsQuery(null,null,Instant.parse("2026-10-21T12:00:00Z"),
                                        Instant.parse("2026-10-21T14:00:00Z"),null)),json).andExpect(status().isOk())
                                .andExpect(jsonPath("$.records[0].reservationNo").value("RES1"))
                                .andReturn().getResponse().getContentAsString();
                        assertThat(reservations).doesNotContain("protected-contact").doesNotContain("contactRef");
                        for(String text:sql.queryForList("SELECT result_json FROM sys_idempotent",String.class))
                            assertThat(text).doesNotContain(raw).doesNotContain(started.get("accessToken").asString());

                    });
        } finally { admin.execute("DROP DATABASE " + database); }
    }
    private static ResultActions tableCall(MockMvc mvc,String path,Object command,ObjectMapper json) throws Exception {
        return mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)));
    }

}
