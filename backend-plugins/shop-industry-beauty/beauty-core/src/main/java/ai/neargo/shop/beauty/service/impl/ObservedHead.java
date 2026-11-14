package ai.neargo.shop.beauty.service.impl;

/** Local head revision observed before matching; revalidated without owner IO under the root lock. */
public record ObservedHead(String kind, String businessNo, long version, String selectedVersionNo) { }
