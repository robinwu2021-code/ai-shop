package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class StandalonePayFixture implements AutoCloseable {
    private Process pay;
    private Path fixture;
    private String payDatabase;
    private JdbcTemplate payProbe;
    public JdbcTemplate jdbc() { return payProbe; }
    public void start(DynamicPropertyRegistry properties,String databaseName) throws Exception {
        start(properties,databaseName,List.of());
    }
    public void start(DynamicPropertyRegistry properties,String databaseName,List<String> ownerArguments) throws Exception {
        System.setProperty("h2.bindAddress", "127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("backend/pay/pay-svc/pom.xml"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Repository root not found");
        Path runtime = root.resolve("backend/pay/pay-svc/target/entitlement-runtime-classpath.txt");
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalStateException("Run scripts/verify-entitlement-two-process.mjs to prepare isolated pay runtime");
        }
        String classpath = Files.readString(runtime);
        assertThat(classpath).doesNotContain("shop-core", "shop-app", "shop-merchant");
        fixture = Files.createTempDirectory("entitlement-two-process-");
        int corePort = port(), payPort = port();
        while (payPort == corePort) payPort = port();
        String token = "isolated-acceptance-internal-token";
        payDatabase = "jdbc:h2:file:" + fixture.resolve("pay").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx384m", "-cp", classpath, "ai.neargo.shop.pay.svc.EntitlementDeploymentFixture",
                "--server.address=127.0.0.1", "--server.port=" + payPort,
                "--spring.datasource.url=" + payDatabase, "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.sql.init.mode=always", "--spring.sql.init.schema-locations="
                        + root.resolve("backend/shop-app/src/test/resources/schema-test.sql").toUri(),
                "--shop.services.internal-token=" + token,
                "--shop.services.targets.PLATFORM=http://127.0.0.1:" + corePort,
                "--shop.pay.stored-value.enabled=true", "--shop.pay.entitlement.use.enabled=true",
                "--shop.pay.entitlement.catalog.enabled=true", "--shop.pay.entitlement.issue.enabled=true",
                "--shop.pay.paid-card.enabled=true",
                "--shop.pay.entitlement.cursor-key=dual-process-test-cursor-key-at-least-32-characters",
                "--entitlement.fixture.ready=" + fixture.resolve("ready"));
        process.command().addAll(List.copyOf(ownerArguments));
        process.environment().put("ENTITLEMENT_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        process.redirectErrorStream(true).redirectOutput(root.resolve("backend/shop-app/target/entitlement-pay-child.log").toFile());
        pay = process.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!Files.exists(fixture.resolve("ready"))) {
            if (!pay.isAlive() || System.nanoTime() > deadline) {
                pay.destroyForcibly();
                throw new IllegalStateException("Pay subprocess startup failed; inspect target/entitlement-pay-child.log");
            }
            Thread.sleep(100);
        }
        assertThat(Long.parseLong(Files.readString(fixture.resolve("ready"))))
                .isNotEqualTo(ProcessHandle.current().pid());
        payProbe = new JdbcTemplate(new DriverManagerDataSource(payDatabase, "sa", ""));
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.port", () -> corePort);
        properties.add("shop.pay.deployment", () -> "standalone");
        final int ownerPort = payPort;
        properties.add("shop.services.targets.PAY", () -> "http://127.0.0.1:" + ownerPort);
        properties.add("shop.services.internal-token", () -> token);
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }
    private static int port() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    @Override public void close() throws Exception {
        if (pay != null) {
            pay.destroy();
            if (!pay.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) pay.destroyForcibly().waitFor();
        }
        if (fixture != null) {
            try (var files = Files.walk(fixture)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
