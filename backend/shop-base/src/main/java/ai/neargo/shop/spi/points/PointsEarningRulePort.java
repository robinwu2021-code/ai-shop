package ai.neargo.shop.spi.points;

import java.util.List;

/** Read-only policy boundary. Does not post points, consume budgets, or choose a type for callers. */
public interface PointsEarningRulePort {
    record Type(String typeCode, String name, String market, String currency, int scale,
                long ratePoints, long rateMinor) {}
    record PublishedRule(String scope, long revision, String mode, long value) {}
    record Query(String typeCode, String currency, String categoryNo, long cashMinor) {}
    record Calculation(Type type, String categoryNo, long cashMinor, String matchedScope,
                       long revision, long ratioBp, long points) {}
    List<Type> availableTypes();
    List<PublishedRule> published(String typeCode);
    Calculation calculate(Query query);
}
