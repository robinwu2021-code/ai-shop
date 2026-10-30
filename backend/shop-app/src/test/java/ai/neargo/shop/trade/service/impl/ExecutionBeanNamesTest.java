package ai.neargo.shop.trade.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.beans.Introspector;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Inspects actual compiled definitions across both owners without bootstrapping either application. */
class ExecutionBeanNamesTest {
    @Test
    void embeddedExecutionOwnersHaveDistinctBeanNames() throws Exception {
        Path root = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent().getParent().getParent();
        var metadata = new CachingMetadataReaderFactory();
        var names = new ArrayList<String>();
        for (String type : new String[] {"policy.ServiceExecutionSchemaVerifier",
                "service.impl.ServiceExecutionFenceCoordinator", "job.ServiceExecutionRecoveryJob"}) {
            var reader = metadata.getMetadataReader(new FileSystemResource(root.resolve(
                    "backend/shop-core/target/classes/ai/neargo/shop/trade/" + type.replace('.', '/') + ".class")));
            var annotation = reader.getAnnotationMetadata().getAnnotationAttributes("org.springframework.stereotype.Component");
            assertThat(annotation).isNotNull();
            String explicit = (String) annotation.get("value");
            names.add(explicit.isBlank() ? Introspector.decapitalize(type.substring(type.lastIndexOf('.') + 1)) : explicit);
        }
        var beauty = metadata.getMetadataReader(new FileSystemResource(root.resolve(
                "backend-plugins/shop-industry-beauty/beauty-core/target/classes/ai/neargo/shop/beauty/config/"
                        + "BeautySourceConfiguration.class"))).getAnnotationMetadata();
        for (var method : beauty.getAnnotatedMethods("org.springframework.context.annotation.Bean")) {
            var annotation = method.getAnnotationAttributes("org.springframework.context.annotation.Bean");
            if (method.getMethodName().startsWith("beautyServiceExecutionRecovery")) {
                var profile = method.getAnnotationAttributes("org.springframework.context.annotation.Profile");
                assertThat(profile).isNotNull();
                assertThat((String[]) profile.get("value")).containsExactly("api");
            }
            String[] explicit = (String[]) annotation.get("name");
            names.add(explicit.length == 0 ? method.getMethodName() : explicit[0]);
        }
        assertThat(names).doesNotHaveDuplicates();
    }
}
