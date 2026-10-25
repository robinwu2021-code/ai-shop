package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record TemplateResolutionCommand(
        PrintScope scope,
        String scene,
        int payloadSchemaVersion,
        String variantCode,
        TemplateSelection selection,
        ResolutionPurpose purpose,
        Set<String> dataScopes) {
    public TemplateResolutionCommand {
        dataScopes = Set.copyOf(Objects.requireNonNull(dataScopes, "dataScopes"));
    }
}
