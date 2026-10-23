package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PdfRenderInput(
        String exportNo,
        PrintArtifactKind artifactKind,
        PdfOutputProfile profile,
        List<PrintLayout> layouts,
        String rendererVersion,
        String fontBundleVersion,
        Instant exportCreatedAt) {
    public PdfRenderInput {
        layouts = List.copyOf(Objects.requireNonNull(layouts, "layouts"));
    }
}
