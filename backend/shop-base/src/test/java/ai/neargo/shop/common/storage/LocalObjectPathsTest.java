package ai.neargo.shop.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalObjectPathsTest {
    @TempDir Path root;

    @Test
    void resolvesObjectKeysAndRejectsTraversal() {
        assertEquals(root.resolve("entity/store/photo.png"),
                LocalObjectPaths.resolve(root, "entity/store/photo.png"));
        assertThrows(IllegalArgumentException.class, () -> LocalObjectPaths.resolve(root, "a/../photo.png"));
        assertThrows(IllegalArgumentException.class, () -> LocalObjectPaths.resolve(root, "/photo.png"));
        assertThrows(IllegalArgumentException.class, () -> LocalObjectPaths.resolve(root, "a\\photo.png"));
    }

    @Test
    void rejectsSymbolicLinksInsideTheObjectRoot() throws IOException {
        Files.createSymbolicLink(root.resolve("alias"), root.getParent());
        assertThrows(IllegalArgumentException.class, () -> LocalObjectPaths.resolve(root, "alias/photo.png"));
    }
}
