package ai.neargo.shop.pay.points.policy;

import java.util.List;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

/** Pauses stop the inactivity clock; overlapping outages are counted once and never reset activity. */
public final class ExpiryPolicy {
    private ExpiryPolicy() { }
    public record Interval(long from,long to) { }
    public static long extend(long base,long lastActive,long asOf,List<Interval> pauses) {
        if (base<lastActive || asOf<lastActive) { throw new IllegalArgumentException("POINTS_EXPIRY_TIME_INVALID"); }
        var clipped=pauses.stream().map(i -> new Interval(Math.max(lastActive,i.from()),Math.min(asOf,i.to())))
                .filter(i -> i.from()<i.to()).sorted(Comparator.comparingLong(Interval::from)).toList();
        var merged=new ArrayList<Interval>();
        for (var interval:clipped) {
            if (merged.isEmpty() || merged.getLast().to()<interval.from()) { merged.add(interval); }
            else {
                var prior=merged.removeLast();
                merged.add(new Interval(prior.from(),Math.max(prior.to(),interval.to())));
            }
        }
        long due=base;
        for (var interval:merged) {
            if (interval.from()>due) { break; }
            due=Math.addExact(due,Math.subtractExact(interval.to(),interval.from()));
        }
        return due;
    }
    /** Complement of available intervals; independent accepting stores are combined by union. */
    public static List<Interval> unavailable(long from,long to,List<Interval> available) {
        var sorted=available.stream().map(i -> new Interval(Math.max(from,i.from()),Math.min(to,i.to())))
                .filter(i -> i.from()<i.to()).sorted(Comparator.comparingLong(Interval::from)).toList();
        var gaps=new ArrayList<Interval>();
        long at=from;
        for (var i:sorted) {
            if (i.from()>at) { gaps.add(new Interval(at,i.from())); }
            at=Math.max(at,i.to());
        }
        if (at<to) { gaps.add(new Interval(at,to)); }
        return List.copyOf(gaps);
    }
    /** Remove outages from one acceptance interval. */
    public static List<Interval> usable(Interval acceptance,List<Interval> outages) {
        return unavailable(acceptance.from(),acceptance.to(),outages);
    }
    public static long regionalDeadline(long activeAt,int idleDays,ZoneId zone) {
        if (activeAt<0 || idleDays<=0 || zone==null) {
            throw new IllegalArgumentException("POINTS_EXPIRY_TIME_INVALID");
        }
        return Instant.ofEpochMilli(activeAt).atZone(zone).plusDays(idleDays).toLocalDate()
                .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
