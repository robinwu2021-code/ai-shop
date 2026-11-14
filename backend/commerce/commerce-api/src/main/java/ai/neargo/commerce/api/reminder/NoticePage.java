package ai.neargo.commerce.api.reminder;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record NoticePage(List<NoticeView> notices, String nextCursor, boolean hasMore) {}
