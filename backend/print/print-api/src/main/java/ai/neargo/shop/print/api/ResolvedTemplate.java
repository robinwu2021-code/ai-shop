package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record ResolvedTemplate(
        String templateNo,
        String templateVersionNo,
        String builtinKey,
        int payloadSchemaVersion,
        String scene,
        String variantCode,
        FrozenJson content,
        FrozenJson resolutionSnapshot) {}
