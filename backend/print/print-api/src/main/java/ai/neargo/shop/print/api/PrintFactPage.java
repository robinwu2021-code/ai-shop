package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintFactPage(List<PrintFactRef> facts, PrintFactCursor nextCursor, boolean hasMore) {
    public PrintFactPage {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
    }
}
