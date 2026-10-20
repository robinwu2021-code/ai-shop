package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Supply the actual isolated pay test runtime to the opt-in two-process acceptance runner. */
class EntitlementDeploymentClasspathTest {
    @Test void writeStandaloneClasspathWithoutAnyCoreBusinessModule() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        assertThat(classpath).isNotBlank().doesNotContain("shop-core", "shop-app", "shop-merchant");
        Files.writeString(Path.of("target/entitlement-runtime-classpath.txt"), classpath);
    }
}
