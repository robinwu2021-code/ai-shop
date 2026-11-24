package ai.neargo.shop.channel.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.MediaStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test void failedLengthChecksKeepCompleteObjectAndLeaveNoStagingBytes() throws Exception {
        var store = new LocalDiskMediaStore(root.toString(), "/uploads", "/media", "test-key");
        var object = root.resolve(KEY);
        store.put(KEY, new ByteArrayInputStream(new byte[]{6, 7}), 2, "image/png");
        assertThatThrownBy(() -> store.put(KEY, new ByteArrayInputStream(new byte[]{9}), 2, "image/png"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.put(KEY, new ByteArrayInputStream(new byte[]{9, 8}), 1, "image/png"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(object).hasBinaryContent(new byte[]{6, 7});
        try (var files = Files.list(object.getParent())) {
            assertThat(files.toList()).containsExactly(object);
        }
    }

    @Test void rejectsSymbolicLinksForStorageAndSigning() throws Exception {
        var storeRoot = Files.createDirectory(root.resolve("uploads"));
        var outside = Files.createDirectory(root.resolve("outside"));
        var external = Files.write(outside.resolve("external.bin"), new byte[]{5});
        Files.createSymbolicLink(storeRoot.resolve("alias"), outside);
        var store = new LocalDiskMediaStore(storeRoot.toString(), "/uploads", "/media", "test-key");
        String unsafe = "alias/S1/qual/202611/proof.png";
        assertThatThrownBy(() -> store.put(unsafe, new ByteArrayInputStream(new byte[]{9}), 1, "image/png"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> store.signedUrl(unsafe, java.time.Duration.ofMinutes(1)))
                .isInstanceOf(BizException.class);
        String linked = "E1/S1/qual/202611/proof.png";
        Files.createDirectories(storeRoot.resolve(linked).getParent());
        Files.createSymbolicLink(storeRoot.resolve(linked), external);
        assertThatThrownBy(() -> store.exists(linked)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> store.openPrivate(linked)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> store.delete(java.util.List.of(linked))).isInstanceOf(BizException.class);
        assertThat(external).hasBinaryContent(new byte[]{5});
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
