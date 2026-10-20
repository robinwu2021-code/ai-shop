package ai.neargo.shop.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
/** Only the Flyway connection is adapted; normal core/pay connections retain their configured semantics. */
@Configuration(proxyBeanMethods=false)
public class FlywayDialectConfig {
    @Bean FlywayConfigurationCustomizer migrationDialect() {
        return configuration -> {
            // Versioned SQL has no operational placeholders; V121 contains a literal Spring @Value in a comment.
            configuration.placeholderReplacement(false);
            configuration.dataSource(new FlywayMariaDbDialectDataSource(configuration.getDataSource()));
        };
    }
}
