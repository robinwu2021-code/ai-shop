package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PdfArtifact(
        byte[] bytes,
        String contentHash,
        int pageCount,
        List<BigDecimal> widthsMm,
        String rendererVersion,
        String fontBundleVersion) {
    public PdfArtifact {
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        widthsMm = List.copyOf(Objects.requireNonNull(widthsMm, "widthsMm"));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
