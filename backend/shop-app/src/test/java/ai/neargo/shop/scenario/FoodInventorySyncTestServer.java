package ai.neargo.shop.scenario;

import ai.neargo.inventory.svc.InventoryApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;

/** Only launched by the native sync fixture: real separate JVM, private env credentials, owned UUID schema. */
public final class FoodInventorySyncTestServer {
    private FoodInventorySyncTestServer() {}
    public static void main(String[] args) throws Exception {
        SpringApplication app=new SpringApplication(InventoryApplication.class,InventoryStandaloneCutoverE2ETest.ServiceInfra.class);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sync-process",Map.ofEntries(
                Map.entry("spring.config.name","sync-native-none"),Map.entry("spring.boot.enableautoconfiguration",false),
                Map.entry("server.port",0),Map.entry("server.address","127.0.0.1"),Map.entry("inventory.enabled",true),
                Map.entry("inventory.write-mode","SVC"),Map.entry("inventory.datasource.url",System.getenv("SYNC_INVENTORY_URL")),
                Map.entry("inventory.datasource.username",System.getenv("INVENTORY_PROJECTION_TEST_USER")),
                Map.entry("inventory.datasource.password",System.getenv("INVENTORY_PROJECTION_TEST_PASSWORD")),
                Map.entry("inventory.instance.code","SHOP"),Map.entry("inventory.instance.allowed-systems","SHOP"),
                Map.entry("inventory.jobs.enabled",false),Map.entry("inventory.admin.token","native-sync-test-only")))));
        var context=app.run();
        Files.writeString(Path.of(System.getenv("SYNC_INVENTORY_PORT_FILE")),context.getEnvironment().getProperty("local.server.port"));
    }
}
