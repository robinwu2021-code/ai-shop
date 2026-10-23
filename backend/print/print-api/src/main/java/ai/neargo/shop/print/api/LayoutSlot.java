package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record LayoutSlot(
        String name, FrozenJson geometry, FrozenJson style, Set<String> allowedValues) {
    public LayoutSlot {
        allowedValues = Set.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
    }
}
