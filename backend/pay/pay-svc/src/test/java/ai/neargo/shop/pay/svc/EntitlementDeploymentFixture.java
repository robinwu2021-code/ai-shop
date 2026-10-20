package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/** Test subprocess only. The runtime is the real pay application, without core classes or owner mocks. */
public final class EntitlementDeploymentFixture {
    private EntitlementDeploymentFixture() { }
    public static void main(String[] args) throws Exception {
        System.setProperty("h2.bindAddress", "127.0.0.1");
        try (var context = SpringApplication.run(PayApplication.class, args)) {
            if (ProcessHandle.current().pid() == Long.parseLong(System.getenv("ENTITLEMENT_PARENT_PID"))) {
                throw new IllegalStateException("Expected a separate pay process");
            }
            Path ready = Path.of(context.getEnvironment().getRequiredProperty("entitlement.fixture.ready"));
            Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
            try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if ("relay".equals(command)) {
                        context.getBean(PayEntitlementOutboxRelay.class).relayPending();
                        Files.writeString(ready.resolveSibling("relay.done"), "done");
                    } else if ("stop".equals(command)) {
                        break;
                    }
                }
            }
        }
    }
}
