package ai.neargo.commerce.api.port;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record EventPage(List<CommerceEvent> events, String nextCursor, boolean hasMore) {}
