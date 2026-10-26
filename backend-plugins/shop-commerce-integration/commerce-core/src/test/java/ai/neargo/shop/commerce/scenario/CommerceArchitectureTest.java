package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class CommerceArchitectureTest {
    private Path root() {
        var directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("backend/pom.xml")))
            directory = directory.getParent();
        if (directory == null) throw new IllegalStateException("Missing reactor root");
        return directory;
    }

    @Test
    void coreAndWebHaveNoIndustryImplementationOrEachOtherDependencies() throws Exception {
        var modules = root().resolve("backend-plugins/shop-commerce-integration");
        String core = Files.readString(modules.resolve("commerce-core/pom.xml"));
        assertThat(core)
                .doesNotContain(
                        "<artifactId>commerce-channel</artifactId>",
                        "<artifactId>commerce-web</artifactId>",
                        "<artifactId>food-core</artifactId>",
                        "<artifactId>shop-core</artifactId>");
        String web = Files.readString(modules.resolve("commerce-web/pom.xml"));
        assertThat(web)
                .doesNotContain(
                        "<artifactId>commerce-core</artifactId>",
                        "<artifactId>commerce-channel</artifactId>",
                        "<artifactId>shop-store-mybatis</artifactId>");
        for (String module :
                List.of("commerce-core", "commerce-web", "commerce-channel", "commerce-server")) {
            assertThat(Files.readString(modules.resolve(module + "/pom.xml")))
                    .doesNotContain("<version>3.", "<version>4.", "<version>5.");
        }
    }

    @Test
    void hostDoesNotBroadScanCommerceAndDefaultBuildHasNoCommerceDependency() throws Exception {
        String app =
                Files.readString(
                        root().resolve(
                                        "backend/shop-app/src/main/java/ai/neargo/shop/ShopApplication.java"));
        assertThat(app).contains("(food|commerce)");
        String pom = Files.readString(root().resolve("backend/shop-app/pom.xml"));
        assertThat(pom.substring(0, pom.indexOf("<profiles>")))
                .doesNotContain("<artifactId>commerce-");
    }
}
