package ai.neargo.shop.scenario;

import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.svc.InventoryApplication;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.PlatformOutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.FoodStockContracts;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.FoodStockSyncService.ConfigCommand;
import ai.neargo.shop.food.service.impl.FoodStockManagementServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockReconciliationServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher;
import ai.neargo.shop.food.service.impl.FoodStockSyncPublisher;
import ai.neargo.shop.food.service.impl.FoodStockSyncServiceImpl;
import ai.neargo.shop.inventory.service.impl.FoodStockProjectionServiceImpl;
import ai.neargo.shop.spi.food.FoodStockSyncRequested;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real FOOD transactions, durable unified outbox, shop adapter and inventory HTTP on two UUID MySQL9 schemas. */
@EnabledIfEnvironmentVariable(named="FOOD_INVENTORY_OUTBOX_NATIVE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoodInventoryOutboxNativeMysqlTest {
    private final String adminUrl=System.getenv("INVENTORY_PROJECTION_TEST_URL");
    private final String user=System.getenv("INVENTORY_PROJECTION_TEST_USER");
    private final String password=System.getenv("INVENTORY_PROJECTION_TEST_PASSWORD");
    private final String suffix=UUID.randomUUID().toString().replace("-","");
    private final String foodDb="food_sync_it_"+suffix,inventoryDb="inv_sync_it_"+suffix;
    private final String entity="E"+suffix.substring(0,16),store="S"+suffix.substring(0,16);
    private final List<String> created=new ArrayList<>();
    private final ObjectMapper json=JsonMapper.builder().build();
    private final AtomicReference<String> target=new AtomicReference<>();
    private final AtomicBoolean unavailable=new AtomicBoolean(),loseNextApply=new AtomicBoolean();
    private final AtomicReference<String> lostEvent=new AtomicReference<>();
    private final AtomicReference<String> baselineActivation=new AtomicReference<>();
    private final AtomicBoolean holdBaselineResume=new AtomicBoolean(true);
    private final Map<String,AtomicInteger> appliedRequests=new ConcurrentHashMap<>();
    private final AtomicInteger requests=new AtomicInteger();
    private final ExecutorService proxyThreads=Executors.newFixedThreadPool(4);
    private HttpServer proxy;
    @TempDir static Path temporary;
    private Process inventory;
    private final AtomicInteger launches=new AtomicInteger();
    private AnnotationConfigApplicationContext food;
    private InventoryReservationClient client;
    private FoodStockProjectionServiceImpl receiver;
    private JdbcTemplate sourceDb;
    private FoodStockManagementServiceImpl stock;
    private FoodStockSyncServiceImpl sync;
    private FoodStockReconciliationServiceImpl reconcile;
    private UnifiedOutboxFacade facade;
    private OutboxRunner runner;
    private String owner;
    private record Item(String number,BigDecimal initial){}

    @Configuration(proxyBeanMethods=false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class SourceInfrastructure {
        @Bean AuditMetaObjectHandler audit(){return new AuditMetaObjectHandler();}
    }
    @BeforeAll void setup() throws Exception {
        try(var c=DriverManager.getConnection(adminUrl,user,password);var sql=c.createStatement()) {
            try(var rs=sql.executeQuery("SELECT VERSION()")){rs.next();assertThat(rs.getString(1)).startsWith("9.");}
            for(String name:List.of(foodDb,inventoryDb)) {
                sql.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");created.add(name);
            }
        }
        var ds=new DriverManagerDataSource(url(foodDb),user,password);
        Flyway.configure().dataSource(ds).locations("classpath:db/industry/food")
                .table("fnb_flyway_history").placeholderReplacement(false).load().migrate();
        // Existing Outbox baseline fixture, followed by the actual unified V471 DDL; no invented CORE history.
        new JdbcTemplate(ds).execute("CREATE TABLE sys_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                +"event_no VARCHAR(64) UNIQUE,aggregate_type VARCHAR(32),aggregate_id VARCHAR(64),event_type VARCHAR(64),"
                +"payload TEXT,status VARCHAR(16),retry_count INT DEFAULT 0,next_retry_at DATETIME,last_error VARCHAR(512),created_at DATETIME,sent_at DATETIME)");
        new ResourceDatabasePopulator(new FileSystemResource("src/main/resources/db/migration/V471__outbox_delivery.sql")).execute(ds);
        startInventory();
        var admin=RestClient.builder().baseUrl(target.get())
                .defaultHeader("X-Inventory-Admin-Token","native-sync-test-only")
                .defaultHeader("X-Inventory-Actor","OPS:sync-test").build();
        var opened=admin.post().uri("/api/inventory/admin/v1/owners").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("systemCode","SHOP","externalRef",entity,"name","Native FOOD sync")).retrieve().body(Map.class);
        owner=(String)opened.get("ownerId");
        // Only the test identity is seeded; no native quantities or posting documents are manufactured.
        new JdbcTemplate(new DriverManagerDataSource(url(inventoryDb),user,password)).update(
                "INSERT INTO inv_location(owner_id,location_id,name,kind,external_ref,status,is_default) VALUES (?,?,?,'WAREHOUSE',?,'ACTIVE',0)",
                owner,"L"+suffix.substring(0,20),"Store",store);
        var credential=admin.post().uri("/api/inventory/admin/v1/systems/SHOP/credentials")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("name","sync-test")).retrieve().body(Map.class);
        startProxy();
        client=InventoryReservationClient.create(()->Optional.of("http://127.0.0.1:"+proxy.getAddress().getPort()),
                (String)credential.get("appKey"),(String)credential.get("appSecret"),Duration.ofMillis(300),Duration.ofSeconds(3),20,Duration.ofMillis(500));
        startFood(false);
    }
    private String url(String database){return adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database+"?allowPublicKeyRetrieval=true&useSSL=false";}
    private void startInventory() throws Exception {
        int attempt=launches.incrementAndGet();Path port=temporary.resolve("inventory-"+attempt+".port");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx768m","-cp",System.getProperty("surefire.test.class.path"),FoodInventorySyncTestServer.class.getName());
        builder.environment().put("SYNC_INVENTORY_URL",url(inventoryDb));
        builder.environment().put("SYNC_INVENTORY_PORT_FILE",port.toString());
        builder.redirectErrorStream(true).redirectOutput(temporary.resolve("inventory-"+attempt+".log").toFile());
        inventory=builder.start();
        assertThat(inventory.pid()).isNotEqualTo(ProcessHandle.current().pid());
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(!Files.exists(port)&&inventory.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
        assertThat(Files.exists(port)).as("inventory child process startup; see "+temporary).isTrue();
        target.set("http://127.0.0.1:"+Files.readString(port).trim());
    }
    private void stopInventory() throws Exception {
        if(inventory!=null){inventory.destroy();if(!inventory.waitFor(15,TimeUnit.SECONDS))inventory.destroyForcibly().waitFor();}
    }
    private void startProxy() throws Exception {
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);proxy.setExecutor(proxyThreads);
        HttpClient transport=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        proxy.createContext("/",exchange->{
            requests.incrementAndGet();int status=503;byte[] response="{\"code\":\"inventory.unavailable\",\"message\":\"injected transport failure\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            byte[] body=exchange.getRequestBody().readAllBytes();String event=null;
            try {
                String path=exchange.getRequestURI().getPath();
                if(path.endsWith("/activate"))baselineActivation.set(json.readTree(body).path("operationKey").asString());
                // Hold subsequent ACTIVATE receipt lookup after page one; source mutations cannot miss BOOTSTRAPPING.
                boolean baselineHeld=holdBaselineResume.get()&&baselineActivation.get()!=null&&path.endsWith("/receipt")
                        &&baselineActivation.get().equals(json.readTree(body).path("eventNo").asString());
                if(!unavailable.get()&&!baselineHeld) {
                    var request=HttpRequest.newBuilder(URI.create(target.get()+exchange.getRequestURI())).timeout(Duration.ofSeconds(3));
                    for(String h:List.of("Authorization","X-Inventory-Owner-Ref","X-Inventory-Actor","Idempotency-Key","Content-Type")) {
                        String value=exchange.getRequestHeaders().getFirst(h);if(value!=null)request.header(h,value);
                    }
                    if(exchange.getRequestURI().getPath().endsWith("/apply")) {
                        event=json.readTree(body).path("eventNo").asString();
                        appliedRequests.computeIfAbsent(event,ignored->new AtomicInteger()).incrementAndGet();
                    }
                    var upstream=transport.send(request.method(exchange.getRequestMethod(),body.length==0?
                            HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(event!=null&&upstream.statusCode()==200&&loseNextApply.compareAndSet(true,false))lostEvent.set(event);
                    else {status=upstream.statusCode();response=upstream.body();}
                }
            }catch(Exception transportFailure) { /* Controlled unavailable HTTP response, never a fake successful receipt. */ }
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });proxy.start();
    }
    private <T>T mapper(Class<T> type) {
        var session=food.getBean(SqlSessionTemplate.class);
        if(!session.getConfiguration().hasMapper(type))session.getConfiguration().addMapper(type);
        return session.getMapper(type);
    }
    private void startFood(boolean run) {
        food=new AnnotationConfigApplicationContext();
        food.getEnvironment().getPropertySources().addFirst(new MapPropertySource("source",Map.of(
                "spring.datasource.url",url(foodDb),"spring.datasource.username",user,"spring.datasource.password",password,
                "mybatis-plus.mapper-locations","classpath:/sync-fixture-no-xml/*.xml")));
        food.register(SourceInfrastructure.class);food.refresh();
        var ds=food.getBean(DataSource.class);var manager=food.getBean(PlatformTransactionManager.class);
        sourceDb=new JdbcTemplate(ds);
        var clients=new DefaultListableBeanFactory();clients.registerSingleton("client",client);
        receiver=new FoodStockProjectionServiceImpl(clients.getBeanProvider(InventoryReservationClient.class),json);
        var ports=new DefaultListableBeanFactory();ports.registerSingleton("receiver",receiver);
        var provider=ports.getBeanProvider(InventoryFoodStockProjectionPort.class);
        facade=new UnifiedOutboxFacade(json);
        var durable=new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM,ds,manager,json);
        var publisher=new PlatformOutboxPublisher(()->facade,manager);
        var captured=new FoodStockSyncPublisher(mapper(StockSyncConfigMapper.class),mapper(StockSyncItemStateMapper.class),
                mapper(StockSyncMappingMapper.class),mapper(StockSyncDeliveryMapper.class),mapper(StockItemMapper.class),
                mapper(StockBalanceMapper.class),publisher,json);
        stock=new FoodStockManagementServiceImpl(mapper(StockItemMapper.class),mapper(StockBalanceMapper.class),mapper(StockCommandMapper.class),
                mapper(StockMovementMapper.class),mapper(StockReservationItemMapper.class),mock(GoodsCatalogQueryPort.class),json,manager,captured);
        sync=new FoodStockSyncServiceImpl(mapper(StockSyncConfigMapper.class),mapper(StockSyncMappingMapper.class),mapper(StockSyncDeliveryMapper.class),
                mapper(StockItemMapper.class),mapper(StockBalanceMapper.class),mapper(StockCommandMapper.class),mapper(ActionLogMapper.class),captured,provider,json,manager);
        reconcile=new FoodStockReconciliationServiceImpl(mapper(StockSyncReconciliationMapper.class),mapper(StockSyncDifferenceMapper.class),
                mapper(StockSyncConfigMapper.class),mapper(StockSyncItemStateMapper.class),mapper(StockBalanceMapper.class),mapper(StockItemMapper.class),
                mapper(StockCommandMapper.class),captured,provider,json,manager);
        var dispatcher=new FoodStockSyncDispatcher(mapper(StockSyncConfigMapper.class),mapper(StockSyncDeliveryMapper.class),mapper(StockItemMapper.class),
                mapper(StockBalanceMapper.class),captured,provider,json,manager,reconcile);
        facade.register(durable,List.of(dispatcher));
        var settings=new OutboxProperties();settings.setConcurrency(2);settings.setRecoveryInterval(Duration.ofMillis(100));
        settings.getRetry().setInitialDelay(Duration.ofSeconds(1));settings.getRetry().setMaxDelay(Duration.ofSeconds(2));settings.getRetry().setMaxAttempts(100);
        facade.setRetryPolicy(settings.retryPolicy());runner=new OutboxRunner(facade,Clock.systemUTC(),settings);
        if(run)runner.start();
    }
    private Item item(String name,int quantity) {
        var i=stock.createItem(entity,store,"IT",new FoodStockContracts.CreateItem("MATERIAL",null,name,"KG","TRACKED"),"item-"+name);
        stock.initialize(entity,store,"IT",new FoodStockContracts.Initialize(i.itemNo(),"TRACKED","ACTIVE",BigDecimal.valueOf(quantity),"INITIAL","Actual test count"),"init-"+name);
        return new Item(i.itemNo(),BigDecimal.valueOf(quantity));
    }
    private void adjust(Item i,int delta,String key) {
        var b=stock.getBalance(entity,store,i.number());
        stock.adjust(entity,store,i.number(),"IT",new FoodStockContracts.Adjust(BigDecimal.valueOf(delta),b.version(),"COUNT","Physical adjustment"),key);
    }
    private void configure(boolean enabled,String key) {
        var c=sync.config(entity,store);sync.configure(entity,store,"IT",new ConfigCommand(enabled,c.version(),"IT"),key);
    }
    private void await(String label,BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<end) {if(condition.getAsBoolean())return;Thread.sleep(50);}
        fail(label+"; "+sourceDb.queryForList("SELECT status,last_error_code,COUNT(*) AS n FROM fnb_stock_sync_delivery GROUP BY status,last_error_code"));
    }
    private long backlog(){return sourceDb.queryForObject("SELECT COUNT(*) FROM fnb_stock_sync_delivery WHERE status NOT IN ('COMPLETED','CANCELLED')",Long.class);}
    private boolean settled(){return backlog()==0&&sourceDb.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery WHERE status NOT IN ('SENT','WARNING')",Long.class)==0;}
    private BigDecimal mirror(Item item){return receiver.queryProjection(new InventoryFoodStockProjectionPort.Scope(entity,store),List.of(item.number())).getFirst().onHand();}

    @Test void realSourceOutboxAndMicroserviceConvergeAcrossBaselineFaultsAndRestart() throws Exception {
        unavailable.set(true);
        var first=item("FIRST",10);
        for(int n=1;n<101;n++)item("BASE"+n,20);
        assertThat(sync.config(entity,store).status()).isEqualTo("OFF");
        assertThat(requests.get()).isZero();assertThat(sourceDb.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
        unavailable.set(false);assertThat(sync.preflight(entity,store).allowed()).isTrue();
        configure(true,"enable-1");runner.start();
        await("first baseline page",()->sourceDb.queryForObject("SELECT baseline_cursor>0 AND status='BOOTSTRAPPING' FROM fnb_stock_sync_config",Boolean.class));
        adjust(first,3,"during-baseline");var added=item("AFTER-BOUNDARY",7);
        assertThat(sync.config(entity,store).status()).isEqualTo("BOOTSTRAPPING");
        holdBaselineResume.set(false);
        await("baseline and source deltas delivered",()->"ACTIVE".equals(sync.config(entity,store).status())&&settled());
        assertThat(mirror(first)).isEqualByComparingTo("13");assertThat(mirror(added)).isEqualByComparingTo("7");
        assertThat(receiver.list(new InventoryFoodStockProjectionPort.Scope(entity,store),1,100).total()).isEqualTo(102);

        loseNextApply.set(true);adjust(first,2,"lost-response");
        await("commit happened and proxy discarded response",()->lostEvent.get()!=null);
        String lost=lostEvent.get();
        await("lost response recovered by original receipt",this::settled);
        assertThat(mirror(first)).isEqualByComparingTo("15");assertThat(appliedRequests.get(lost).get()).isEqualTo(1);
        assertThat(sourceDb.queryForObject("SELECT attempts FROM fnb_stock_sync_delivery WHERE sync_no=?",Integer.class,lost)).isGreaterThanOrEqualTo(2);
        var inv=new JdbcTemplate(new DriverManagerDataSource(url(inventoryDb),user,password));
        assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_food_stock_sync_receipt WHERE event_no=?",Long.class,lost)).isEqualTo(1);

        unavailable.set(true);adjust(first,1,"outage");
        assertThat(stock.getBalance(entity,store,first.number()).onHand()).isEqualByComparingTo("16");
        await("unavailable is a durable retry",()->sourceDb.queryForObject("SELECT COUNT(*) FROM fnb_stock_sync_delivery WHERE status='RETRYABLE'",Long.class)>0);
        stopInventory();startInventory();unavailable.set(false);
        await("receiver restart and retry",this::settled);assertThat(mirror(first)).isEqualByComparingTo("16");

        runner.close();runner.awaitIdle(Duration.ofSeconds(5));adjust(first,1,"source-restart");
        assertThat(backlog()).isGreaterThan(0);food.close();startFood(true);
        await("source runtime restart recovers SQL queue",this::settled);assertThat(mirror(first)).isEqualByComparingTo("17");
        var task=reconcile.create(entity,store,"IT",List.of(),"reconcile");
        await("paginated reconciliation",()->"COMPLETED".equals(reconcile.get(entity,store,task.reconciliationNo(),1,20).status())&&settled());
        var result=reconcile.get(entity,store,task.reconciliationNo(),1,20);
        assertThat(result.checkedCount()).isEqualTo(102);assertThat(result.differenceCount()).isZero();
        assertThat(result.consistencyMode()).isEqualTo("PAGE_OBSERVED");

        String priorPayload=sourceDb.queryForObject("SELECT payload_snapshot FROM fnb_stock_sync_delivery WHERE sync_no=?",String.class,lost);
        var original=json.readValue(priorPayload,FoodStockSyncRequested.class);
        unavailable.set(true);configure(false,"disable");
        assertThat(sync.config(entity,store).status()).isEqualTo("DISABLING");adjust(first,1,"during-stop");
        unavailable.set(false);await("confirmed OFF barrier",()->"OFF".equals(sync.config(entity,store).status())&&settled());
        long events=sourceDb.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class);adjust(first,2,"while-off");
        assertThat(sourceDb.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isEqualTo(events);
        assertThat(mirror(first)).isEqualByComparingTo("17");
        configure(true,"enable-2");await("fresh generation baseline",()->"ACTIVE".equals(sync.config(entity,store).status())&&settled());
        assertThat(sync.config(entity,store).generation()).isEqualTo(2);assertThat(mirror(first)).isEqualByComparingTo("20");
        assertThatThrownBy(()->receiver.apply(new InventoryFoodStockProjectionPort.Scope(entity,store),1,"LATE-NEW-EVENT",original.payloadHash(),original.snapshot()))
                .isInstanceOfSatisfying(BizException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        inv=new JdbcTemplate(new DriverManagerDataSource(url(inventoryDb),user,password));
        assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_stock_balance WHERE owner_id=?",Long.class,owner)).isZero();
        assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger WHERE owner_id=?",Long.class,owner)).isZero();
    }
    @AfterAll void cleanup() throws Exception {
        if(runner!=null)runner.close();if(food!=null)food.close();stopInventory();
        if(proxy!=null)proxy.stop(0);proxyThreads.shutdownNow();
        try(var c=DriverManager.getConnection(adminUrl,user,password);var sql=c.createStatement()) {
            for(String database:created)sql.execute("DROP DATABASE `"+database+"`");
        }
    }
}
