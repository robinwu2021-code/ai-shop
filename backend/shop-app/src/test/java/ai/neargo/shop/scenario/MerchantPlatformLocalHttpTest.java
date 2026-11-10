package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.service.AuthService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit loopback-only Dart -> actual server acceptance; test-profile H2, no external service. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.address=127.0.0.1")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="BAPP_PLATFORM_APP_ROOT", matches="/.*")
class MerchantPlatformLocalHttpTest {
    @LocalServerPort int port;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Test void realPasswordDartSelectedStoreJourney() throws Exception {
        var fixture = new MerchantPlatformOnboardingFlowTest();
        fixture.context=context; fixture.json=json; fixture.otp=otp; fixture.jdbc=jdbc;
        var shop=fixture.shop();
        String user=jdbc.queryForObject("SELECT user_no FROM mch_account WHERE entity_no=? AND is_owner=TRUE",String.class,shop.entity());
        String phone=jdbc.queryForObject("SELECT identity_value FROM usr_identity WHERE user_no=? AND identity_type='PHONE'",String.class,user);
        String password=UUID.randomUUID().toString(); auth.setPassword(user,password);
        String dartTest=System.getenv().getOrDefault("BAPP_PLATFORM_DART_TEST", "merchant_platform_runtime_test.dart");
        if (!java.util.Set.of("merchant_platform_runtime_test.dart", "store_relink_runtime_test.dart").contains(dartTest))
            throw new IllegalArgumentException("Unknown local Dart acceptance");
        String target=shop.entity();
        if ("store_relink_runtime_test.dart".equals(dartTest)) {
            var relink=new StoreRelinkPrecheckFlowTest(); relink.jdbc=jdbc; target=relink.target(shop);
        }
        Path output=Files.createTempDirectory("bapp-platform-http-");
        Path ready=output.resolve("ready.json"), log=output.resolve("flutter-http.log");
        Files.createFile(ready,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(ready,json.writeValueAsString(Map.of("hostOrigin","http://127.0.0.1:"+port,
                "principal",phone,"password",password,"entityNo",shop.entity(),"storeNo",shop.store(),"targetEntityNo",target)));
        try {
            Path app=Path.of(System.getenv("BAPP_PLATFORM_APP_ROOT")).toAbsolutePath();
            var process=new ProcessBuilder(app.resolve("tooling/flutterw").toString(),"test","--no-pub",
                    "test/integration/"+dartTest,"--reporter=expanded")
                    .directory(app.resolve("apps/merchant_app").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("BAPP_PLATFORM_READY",ready.toString());
            var running=process.start();
            if (!running.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)) {
                running.destroyForcibly();
                throw new AssertionError("Local Dart acceptance timeout; "+log);
            }
            assertThat(running.exitValue()).as("local Dart evidence: "+log).isZero();
            Files.writeString(output.resolve("result.json"),"{\"status\":\"PASS\",\"database\":\"test-profile H2\",\"realPassword\":true,\"remoteAccess\":false,\"privateChannelWritten\":false}\n");
            System.out.println("PLATFORM_LOCAL_HTTP_PASS evidence="+output);
        } finally {
            Files.deleteIfExists(ready);
        }
    }
}
