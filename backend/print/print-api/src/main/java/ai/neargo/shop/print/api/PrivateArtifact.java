package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrivateArtifact(
        String objectKey, byte[] bytes, String contentHash, String contentType) {
    public PrivateArtifact {
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
