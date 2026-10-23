package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record FrozenJson(String canonicalJson, String sha256) {
    public FrozenJson {
        Objects.requireNonNull(canonicalJson);
        Objects.requireNonNull(sha256);
        if (canonicalJson.length() > 3 * 1024 * 1024 || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("frozen json");
        if (!PrintHashes.sha256(canonicalJson).equals(sha256))
            throw new IllegalArgumentException("snapshot hash mismatch");
    }
}
