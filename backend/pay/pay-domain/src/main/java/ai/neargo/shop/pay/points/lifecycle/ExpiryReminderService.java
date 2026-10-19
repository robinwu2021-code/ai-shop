package ai.neargo.shop.pay.points.lifecycle;

/** Creates a durable reminder once per inactivity revision, separate from the income movement. */
public interface ExpiryReminderService {
    boolean prepare(String typeCode, String userNo, long now, int leadDays);

    boolean current(Reminder reminder);

    default boolean current(Reminder reminder, long now) {
        return now < reminder.expireAt() && current(reminder);
    }

    record Reminder(
            String typeCode,
            String userNo,
            long revision,
            long expireAt,
            long points,
            String dedupKey) {}
}
