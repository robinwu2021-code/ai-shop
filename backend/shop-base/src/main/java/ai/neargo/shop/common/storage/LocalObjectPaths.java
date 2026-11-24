package ai.neargo.shop.common.storage;

import java.nio.file.Files;
import java.nio.file.Path;

/** Translates a stored relative object key under a service-owned filesystem root. */
public final class LocalObjectPaths {
    private LocalObjectPaths() {}

    public static Path resolve(Path root, String key) {
        if (key == null || key.isBlank() || key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0
                || key.contains("//") || key.endsWith("/")) {
            throw new IllegalArgumentException("Invalid local object key");
        }
        var objectRoot = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(objectRoot)) {
            throw new IllegalArgumentException("Object root is a symbolic link");
        }
        var relative = Path.of(key);
        if (relative.isAbsolute()) throw new IllegalArgumentException("Object key must be relative");
        var target = objectRoot;
        for (Path segment : relative) {
            if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                throw new IllegalArgumentException("Invalid local object key segment");
            }
            target = target.resolve(segment);
            if (Files.isSymbolicLink(target)) {
                throw new IllegalArgumentException("Symbolic links are not object paths");
            }
        }
        return target;
    }
}
