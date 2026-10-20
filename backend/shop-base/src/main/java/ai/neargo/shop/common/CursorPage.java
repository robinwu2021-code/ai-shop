package ai.neargo.shop.common;

import java.util.List;

/** Immutable keyset page; a null nextCursor is the only end marker, never an invented total count. */
public record CursorPage<T>(List<T> records, String nextCursor, boolean hasMore) {
    public CursorPage(List<T> records, String nextCursor) {
        this(records, nextCursor, nextCursor != null);
    }

    public CursorPage {
        records = List.copyOf(records);
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("Cursor and continuation flag disagree");
        }
        if (nextCursor != null && (nextCursor.isBlank() || nextCursor.length() > 512)) {
            throw new IllegalArgumentException("Invalid next cursor");
        }
    }
}
