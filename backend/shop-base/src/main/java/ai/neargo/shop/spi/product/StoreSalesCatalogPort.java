package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Store-owned presentation facts only; price, shelf, capacity and trading authority remain with their owners. */
public interface StoreSalesCatalogPort {
    Snapshot read(String entityNo, String storeNo, Instant at);

    enum SalesMode { UNRESTRICTED, WINDOWED }

    record Group(String key, String title, int position, Boolean visible) {
        public Group {
            reference(key, 32);
            if (title == null || title.isBlank() || title.length() > 64 || position < 0 || visible == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }
    /** Monday is the first mask character; intervals are half-open and never implicitly cross midnight. */
    record Window(String daysMask, int startMin, int endMin) {
        public Window {
            if (daysMask == null || !daysMask.matches("[01]{7}") || !daysMask.contains("1")
                    || startMin < 0 || endMin > 1440 || startMin >= endMin) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        public boolean includes(Instant at, String timezone) {
            var local = at.atZone(ZoneId.of(timezone));
            int minute = local.getHour() * 60 + local.getMinute();
            return daysMask.charAt(local.getDayOfWeek().getValue() - 1) == '1'
                    && minute >= startMin && minute < endMin;
        }
    }
    record Entry(String skuNo, String groupKey, int position, Boolean visible,
                 SalesMode salesMode, List<Window> windows) {
        public Entry {
            reference(skuNo, 64);
            if (groupKey != null) { reference(groupKey, 32); }
            if (position < 0 || visible == null || salesMode == null || windows == null
                    || windows.size() > 16 || windows.stream().anyMatch(w -> w == null)
                    || (salesMode == SalesMode.UNRESTRICTED) != windows.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            windows = windows.stream().sorted(Comparator.comparing(Window::daysMask)
                    .thenComparingInt(Window::startMin).thenComparingInt(Window::endMin)).distinct().toList();
        }
        public boolean saleableAt(Instant at, String timezone) {
            return salesMode == SalesMode.UNRESTRICTED || windows.stream().anyMatch(w -> w.includes(at, timezone));
        }
    }
    record Definition(String timezone, List<Group> groups, List<Entry> entries) {
        public Definition {
            if (timezone == null || timezone.length() > 64) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            try { ZoneId.of(timezone); }
            catch (RuntimeException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            if (groups == null || groups.size() > 50 || entries == null || entries.size() > 200
                    || groups.stream().anyMatch(g -> g == null) || entries.stream().anyMatch(e -> e == null)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var keys = new HashSet<String>();
            for (Group group : groups) {
                if (!keys.add(group.key())) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            }
            var skus = new HashSet<String>();
            for (Entry entry : entries) {
                if (!skus.add(entry.skuNo()) || entry.groupKey() != null && !keys.contains(entry.groupKey())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            groups = groups.stream().sorted(Comparator.comparingInt(Group::position)
                    .thenComparing(Group::key)).toList();
            entries = entries.stream().sorted(Comparator.comparingInt(Entry::position)
                    .thenComparing(Entry::skuNo)).toList();
        }
        public Entry visibleEntry(String skuNo) {
            return entries.stream().filter(e -> e.skuNo().equals(skuNo) && e.visible())
                    .filter(e -> e.groupKey() == null || groups.stream()
                            .anyMatch(g -> g.key().equals(e.groupKey()) && g.visible())).findFirst().orElse(null);
        }
    }
    record PublishReq(Long expectedRevision, OffsetDateTime validFrom, String timezone,
                      List<Group> groups, List<Entry> entries) {
        public PublishReq {
            if (expectedRevision == null || expectedRevision < 0 || validFrom == null
                    || validFrom.getYear() < 1000 || validFrom.getYear() > 9998) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            validFrom = validFrom.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            var definition = new Definition(timezone, groups, entries);
            groups = definition.groups();
            entries = definition.entries();
        }
        public Definition definition() { return new Definition(timezone, groups, entries); }
    }
    record Snapshot(String entityNo, String storeNo, String catalogNo, long headRevision, String versionNo,
                    long effectiveRevision, OffsetDateTime validFrom, boolean configured,
                    Definition definition, String snapshotHash) { }

    private static void reference(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
