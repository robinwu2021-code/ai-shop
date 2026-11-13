package ai.neargo.shop.channel.media;

import ai.neargo.shop.media.MediaStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LocalStorageRootTest {
    @TempDir Path root;
    private static final String KEY = "E1/S1/goods/202611/object.png";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalDiskMediaStore.class, CosMediaStore.class)
            .withPropertyValues("shop.media.sign-secret=test-key");

    @Test void rootControlsPhysicalLocationWithoutChangingObjectKey() {
        runner.withPropertyValues("shop.storage.root=" + root).run(context -> {
            assertThat(context).hasSingleBean(MediaStore.class).hasSingleBean(LocalDiskMediaStore.class);
            MediaStore store = context.getBean(MediaStore.class);
            byte[] bytes = {1, 2, 3};
            store.put(KEY, new ByteArrayInputStream(bytes), bytes.length, "image/png");
            assertThat(Files.readAllBytes(root.resolve("uploads").resolve(KEY))).isEqualTo(bytes);
            assertThat(store.publicUrl(KEY)).isEqualTo("/uploads/" + KEY);
            assertThat(store.exists(KEY)).isTrue();
            store.delete(java.util.List.of(KEY));
            assertThat(store.exists(KEY)).isFalse();
        });
    }

    @Test void existingUploadDirectoryOverridesNewRoot() {
        Path legacy = root.resolve("legacy");
        runner.withPropertyValues("shop.storage.root=" + root.resolve("new"), "shop.upload.dir=" + legacy)
                .run(context -> {
                    MediaStore store = context.getBean(MediaStore.class);
                    store.put(KEY, new ByteArrayInputStream(new byte[]{9}), 1, "image/png");
                    assertThat(legacy.resolve(KEY)).hasBinaryContent(new byte[]{9});
                    assertThat(root.resolve("new/uploads").resolve(KEY)).doesNotExist();
                });
    }

    @Test void selectingCosDoesNotActivateLocalStorage() {
        runner.withPropertyValues("shop.media.provider=cos", "shop.storage.root=" + root,
                "shop.cos.secret-id=test-id", "shop.cos.secret-key=test-key", "shop.cos.region=ap-guangzhou",
                "shop.cos.bucket=test-1300000000").run(context -> {
                    assertThat(context).hasSingleBean(MediaStore.class).hasSingleBean(CosMediaStore.class)
                            .doesNotHaveBean(LocalDiskMediaStore.class);
                    assertThat(context.getBean(MediaStore.class).publicUrl(KEY)).endsWith("/" + KEY);
                    assertThat(root.resolve("uploads")).doesNotExist();
                });
    }
}
