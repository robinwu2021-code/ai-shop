package ai.neargo.shop.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

/** Owner-local dependencies loaded reflectively in the same module profile as the real resource services. */
@Configuration(proxyBeanMethods = false)
@Import(ResourceOccupationFixtureConfiguration.Imports.class)
public class ResourceOccupationFixtureConfiguration {
    static class Imports implements ImportSelector {
        @Override public String[] selectImports(AnnotationMetadata metadata) {
            return new String[]{"ai.neargo.shop.merchant.policy.ResourceOccupationGuard",
                    "ai.neargo.shop.merchant.policy.ResourceOccupationSchemaVerifier"};
        }
    }
}
