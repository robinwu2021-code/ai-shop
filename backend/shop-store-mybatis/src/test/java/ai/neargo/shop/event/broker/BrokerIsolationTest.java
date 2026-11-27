package ai.neargo.shop.event.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 换中间件的前提（ADR-067 决策 9）：中间件厂商的类只能出现在它自己的实现包里。
 * 扫描面：后端全部模块的 {@code src/main/java}。
 */
@DisplayName("中间件厂商代码只在实现包里")
class BrokerIsolationTest {

    private static final List<String> VENDOR_IMPORTS = List.of("import org.springframework.amqp", "import com.rabbitmq");
    private static final String RABBIT_PACKAGE = "/ai/neargo/shop/event/rabbit/";

    @Test
    void vendorImportsStayInImplementationPackage() throws IOException {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(backend)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains(RABBIT_PACKAGE))
                    .filter(BrokerIsolationTest::importsVendor)
                    .map(p -> backend.relativize(p).toString())
                    .toList();
        }
        assertThat(offenders).as("AMQP/RabbitMQ 的类只许在 event/rabbit 包里用，其余代码只认 MessageBroker").isEmpty();
    }

    private static boolean importsVendor(Path p) {
        try {
            return Files.readAllLines(p).stream().anyMatch(l -> VENDOR_IMPORTS.stream().anyMatch(l::startsWith));
        } catch (IOException e) {
            return false;
        }
    }
}
