package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;
import ai.neargo.shop.spi.reach.ConsumerProfile;

import java.util.HashSet;
import java.util.Set;

/**
 * 对一家店的范围项做与 {@link DbHitFinder} <b>同一语义</b>的集合成员判断（ADR-034）。
 *
 * <p>用在没有库、或不该走库的场合：反向展开（这家店覆盖哪些小区 —— 要对上万个候选逐个判，走库是上万次查询）、
 * 保存前预览（范围项还没落库）。
 *
 * <p>两者结果必须相等，由 {@code HitFinderParityTest} 守着。改这里就要改那边，反之亦然 ——
 * 它们是一个语义的两种机制，不是两份规则。
 */
public final class InMemoryHitFinder {

    private InMemoryHitFinder() {
    }

    public static StoreHits hitsFor(StoreItems items, ConsumerProfile profile) {
        Set<String> ancestors = new HashSet<>(profile.ancestors());
        Set<String> tokens = new HashSet<>(profile.cellTokens());
        StoreHits.Builder b = new StoreHits.Builder();

        for (MchServiceArea a : items.areas()) {
            boolean exclude = MchServiceArea.MODE_EXCLUDE.equals(a.getMode());
            // 纳入要生效；排除不看 status —— 缩小范围不需要审核
            if (!exclude && !MchServiceArea.ACTIVE.equals(a.getStatus())) {
                continue;
            }
            if (a.getRefCode() == null || a.getRefCode().isBlank()) {
                continue;
            }
            if (MchServiceArea.ADMIN_LEVELS.contains(a.getLevel())) {
                if (ancestors.contains(a.getRefCode())) {
                    b.area(a.getAreaNo(), exclude);
                }
            } else if (MchServiceArea.LEVEL_COMMUNITY.equals(a.getLevel())) {
                if (a.getRefCode().equals(profile.communityNo()) || a.getRefCode().equals(profile.parentNo())) {
                    b.area(a.getAreaNo(), exclude);
                }
            }
            // POLYGON 经网格行命中；UNLIMITED 是门店属性，走快照 —— 都不在这里
        }
        for (MchServiceAreaCell c : items.cells()) {
            if (tokens.contains(c.getCellId())) {
                b.cell(c.getAreaNo(), MchServiceArea.MODE_EXCLUDE.equals(c.getMode()),
                        Boolean.TRUE.equals(c.getBoundary()));
            }
        }
        return b.build();
    }
}
