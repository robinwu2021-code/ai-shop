package ai.neargo.shop.print.channel;

import ai.neargo.shop.print.api.PrivateArtifact;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.print.channel.port.storage.PrivateLocalArtifactStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class PrivateArtifactRootTest {
    @TempDir Path root;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrivateLocalArtifactStore.class);
    private static final String KEY = "MAIN/TW/JOB1/receipt.pdf";
    private static final byte[] BYTES = "%PDF-1.4\ntest\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Test void globalRootPreservesPrivateCloudObjectSuffix() {
        runner.withPropertyValues("shop.storage.root=" + root).run(context -> {
            var store = context.getBean(PrivateLocalArtifactStore.class);
            store.put(new PrivateArtifact(KEY, BYTES, PrintHashes.sha256(BYTES), "application/pdf"));
            assertThat(root.resolve("print-private").resolve(KEY)).hasBinaryContent(BYTES);
            assertThat(store.verify(KEY).sha256()).isEqualTo(PrintHashes.sha256(BYTES));
        });
    }

    @Test void explicitPrintRootRemainsAuthoritative() {
        Path explicit = root.resolve("legacy-print");
        runner.withPropertyValues("shop.storage.root=" + root.resolve("new"), "shop.print.artifacts.root=" + explicit)
                .run(context -> {
                    var store = context.getBean(PrivateLocalArtifactStore.class);
                    store.put(new PrivateArtifact(KEY, BYTES, PrintHashes.sha256(BYTES), "application/pdf"));
                    assertThat(explicit.resolve(KEY)).hasBinaryContent(BYTES);
                    assertThat(root.resolve("new/print-private").resolve(KEY)).doesNotExist();
                });
    }
}
