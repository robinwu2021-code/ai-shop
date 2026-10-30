package ai.neargo.inventory.svc;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Mapping;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingQuery;
import ai.neargo.inventory.api.projection.StockProjectionContracts.SessionCommand;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Apply;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Query;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Items;
import ai.neargo.inventory.client.InventoryProjection;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP, credentials, migrations, transactions and restart on one owned UUID MySQL9 database. */
@EnabledIfEnvironmentVariable(named="INVENTORY_PROJECTION_NATIVE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockProjectionNativeMysqlTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final String catalog="inv_projection_it_"+UUID.randomUUID().toString().replace("-","");
    private final String user=System.getenv("INVENTORY_PROJECTION_TEST_USER");
    private final String password=System.getenv("INVENTORY_PROJECTION_TEST_PASSWORD");
    private final String adminUrl=System.getenv("INVENTORY_PROJECTION_TEST_URL");
    private ServletWebServerApplicationContext context;
    private String url;
    @BeforeAll void open() throws Exception {
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            try(var v=s.executeQuery("SELECT VERSION()")){v.next();assertThat(v.getString(1)).startsWith("9.");}
            s.execute("CREATE DATABASE `"+catalog+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");
        }
        url=adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+catalog+"?useSSL=false&allowPublicKeyRetrieval=true";
        start();
    }
    private void start() {
        SpringApplication app=new SpringApplication(InventoryApplication.class);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("projection-native",Map.of(
                "server.port",0,"server.address","127.0.0.1","inventory.datasource.url",url,
                "inventory.datasource.username",user,"inventory.datasource.password",password,
                "inventory.instance.code","SHOP","inventory.instance.allowed-systems","SHOP",
                "inventory.jobs.enabled",false,"inventory.admin.token","native-projection-only"))));
        context=(ServletWebServerApplicationContext)app.run();
    }
    @AfterAll void close() throws Exception {
        if(context!=null)context.close();
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            s.execute("DROP DATABASE `"+catalog+"`");
        }
    }
    private JdbcTemplate db(){return new JdbcTemplate(context.getBean("invDataSource",DataSource.class));}
    private record Fixture(String entity,String owner,InventoryProjection client){}
    private Fixture fixture() {
        var platform=context.getBean(ServicePlatformService.class);
        String entity="E"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        String owner=platform.openOwner("SHOP",entity,"Native mirror","IT");
        String location=context.getBean(LocationService.class).createWarehouse(owner,"Store","IT");
        db().update("UPDATE inv_location SET external_ref='STORE' WHERE location_id=?",location);
        var credential=platform.issueSystemCredential("SHOP","mirror",Set.of(),null,"IT");
        var client=InventoryReservationClient.create(()->Optional.of("http://127.0.0.1:"+context.getWebServer().getPort()),
                credential.appKey(),credential.appSecret(),Duration.ofSeconds(3),Duration.ofSeconds(10),5,Duration.ofSeconds(1));
        return new Fixture(entity,owner,client.projection(entity,"FOOD_SYNC"));
    }
    private Scope scope(Fixture f){return new Scope(f.entity(),"STORE");}
    private Snapshot stock(long version,String qty){return new Snapshot("LOCAL","KG","TRACKED","ACTIVE",
            new BigDecimal(qty),new BigDecimal("2.000000"),version,LocalDateTime.of(2026,10,30,8,0),null,null,null);}
    private static String hash(Scope s,long generation,Snapshot p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsString(new Object[]{s,generation,p}).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void snapshotVersionReceiptBarrierAndRestartKeepOneOriginalIntent() throws Exception {
        var f=fixture();var s=scope(f);var api=f.client();
        var started=api.activate(new SessionCommand(s,1,"ACT-1"));
        assertThat(api.activate(new SessionCommand(s,1,"ACT-1"))).isEqualTo(started);
        var p=stock(1,"10.000000");String h=hash(s,1,p);
        var accepted=api.apply(new Apply(s,1,"EVENT-1",h,p));
        assertThat(accepted.status()).isEqualTo("APPLIED");
        assertThat(api.apply(new Apply(s,1,"EVENT-1",h,p))).isEqualTo(accepted);
        assertThat(api.apply(new Apply(s,1,"SAME-VERSION",h,p)).status()).isEqualTo("UNCHANGED");
        var newer=stock(2,"12.000000");api.apply(new Apply(s,1,"EVENT-2",hash(s,1,newer),newer));
        assertThat(api.apply(new Apply(s,1,"OLD-VERSION",h,p)).status()).isEqualTo("STALE");
        assertThat(api.receipt(new Query(s,"EVENT-1",h)).receipt()).isEqualTo(accepted);
        assertThatThrownBy(()->api.apply(new Apply(s,1,"EVENT-1",hashUnchecked(s,1,newer),newer)))
                .isInstanceOfSatisfying(InventoryException.class,e->assertThat(e.error()).isEqualTo(InventoryError.CONFLICT));
        assertThat(api.list(s,1,20).total()).isEqualTo(1);
        assertThat(api.get(s,"LOCAL").stock().sourceVersion()).isEqualTo(2);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM inv_stock_balance WHERE owner_id=?",Long.class,f.owner())).isZero();
        context.close();start(); // Fresh JVM beans and TCP port, same actual committed MySQL data.
        assertThat(api.receipt(new Query(s,"EVENT-1",h)).receipt()).isEqualTo(accepted);
        assertThat(api.query(new Items(s,List.of("LOCAL"))).getFirst().sourceVersion()).isEqualTo(2);
        api.deactivate(new SessionCommand(s,1,"OFF-1"));
        assertThat(api.get(s,"LOCAL").syncStatus()).isEqualTo("OFF");
        assertThatThrownBy(()->api.apply(new Apply(s,1,"LATE",h,p))).isInstanceOf(InventoryException.class);
        api.activate(new SessionCommand(s,2,"ACT-2"));
        assertThatThrownBy(()->api.apply(new Apply(s,1,"OLD-GENERATION",h,p))).isInstanceOf(InventoryException.class);
        api.apply(new Apply(s,2,"BASE-2",hash(s,2,p),p));
        assertThat(api.get(s,"LOCAL").generation()).isEqualTo(2);
    }
    private static String hashUnchecked(Scope s,long g,Snapshot p){try{return hash(s,g,p);}catch(Exception e){throw new AssertionError(e);}}
    @Test void authenticatedOwnerCannotWriteAnotherSourceScopeAndFrameworkRejectsMalformedInput() {
        var f=fixture();var api=f.client();
        assertThatThrownBy(()->api.activate(new SessionCommand(new Scope("OTHER","STORE"),1,"BAD")))
                .isInstanceOfSatisfying(InventoryException.class,e->assertThat(e.error()).isEqualTo(InventoryError.FORBIDDEN));
        assertThatThrownBy(()->api.mappings(new MappingQuery(null,List.of())))
                .isInstanceOfSatisfying(InventoryException.class,e->assertThat(e.error()).isEqualTo(InventoryError.BAD_REQUEST));
        assertThatThrownBy(()->api.list(scope(f),0,20))
                .isInstanceOfSatisfying(InventoryException.class,e->assertThat(e.error()).isEqualTo(InventoryError.BAD_REQUEST));
        assertThatThrownBy(()->api.get(scope(f),"X".repeat(33)))
                .isInstanceOfSatisfying(InventoryException.class,e->assertThat(e.error()).isEqualTo(InventoryError.BAD_REQUEST));
        assertThat(api.mappings(new MappingQuery(scope(f),List.of(new Mapping("LOCAL","KG",null,null,null)))))
                .containsExactly(new MappingResult("LOCAL",true,null));
    }
    @Test void untrackedDoesNotTurnIntoZeroOrNativeStock() throws Exception {
        var f=fixture();var s=scope(f);var api=f.client();api.activate(new SessionCommand(s,1,"ACT"));
        var p=new Snapshot("UNTRACKED","EA","UNTRACKED","ACTIVE",null,null,1,LocalDateTime.now(),null,null,null);
        api.apply(new Apply(s,1,"U1",hash(s,1,p),p));
        assertThat(api.get(s,"UNTRACKED").stock().onHand()).isNull();
        assertThat(api.get(s,"UNTRACKED").stock().reservedQty()).isNull();
        assertThat(db().queryForObject("SELECT COUNT(*) FROM inv_stock_balance WHERE owner_id=?",Long.class,f.owner())).isZero();
    }
}
