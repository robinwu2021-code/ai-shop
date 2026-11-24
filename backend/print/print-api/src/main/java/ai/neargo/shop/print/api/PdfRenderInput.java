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
        Instant exportCreatedAt,
        InvoiceDocumentProvenance invoiceDocument) {
    public PdfRenderInput {
        layouts = List.copyOf(Objects.requireNonNull(layouts, "layouts"));
    }

    public PdfRenderInput(String exportNo, PrintArtifactKind artifactKind, PdfOutputProfile profile,
            List<PrintLayout> layouts, String rendererVersion, String fontBundleVersion,
            Instant exportCreatedAt) {
        this(exportNo, artifactKind, profile, layouts, rendererVersion, fontBundleVersion, exportCreatedAt, null);
    }

    public record InvoiceDocumentProvenance(PrintFactRef fact, FrozenJson templateResolution) {}
}
