package ai.neargo.shop.common;

import java.util.List;

/** Immutable keyset page; a null nextCursor is the only end marker, never an invented total count. */
public record CursorPage<T>(List<T> records, String nextCursor) {
    public CursorPage {
        records = List.copyOf(records);
        if (nextCursor != null && (nextCursor.isBlank() || nextCursor.length() > 512)) {
            throw new IllegalArgumentException("Invalid next cursor");
        }
    }
}
