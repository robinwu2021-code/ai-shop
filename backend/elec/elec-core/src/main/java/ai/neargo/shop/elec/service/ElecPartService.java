package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.PartDtos.LookupLine;
import ai.neargo.shop.elec.dto.PartDtos.PartHit;
import ai.neargo.shop.elec.dto.PartDtos.SearchResult;

import java.util.List;

/** 买家查料号。只读料号库与投影表，<b>碰不到 elc_stock</b>。 */
public interface ElecPartService {

    /**
     * 搜料号：完全一致 → 开头一致 → 中段一致，同档里有货的在前；一条都没有时退到更短的前缀给近似结果。
     *
     * @param suggest true = 边打字边提示（只返回 8 条、不记入搜索需求）
     */
    SearchResult search(String keyword, boolean suggest);

    /** 批量查：一行一个料号（可带厂牌与数量），最多 50 行 */
    List<LookupLine> lookup(String text);

    PartHit detail(String partNo);

    /**
     * 运营端搜料号：与 {@link #search} <b>同一套命中</b>（开头 / 中段 / 「厂牌 + 料号」限定），但
     * <ul>
     *   <li><b>不记入搜索需求</b> —— 运营每查一次都算成一次买家需求，「大家在找什么」就被运营自己刷满了</li>
     *   <li>不做近似退位 —— 运营要的是「库里有没有这个」，猜给他一个相近的反而误事</li>
     * </ul>
     *
     * @return 命中的料号，按命中档（EXACT → PREFIX → CONTAINS）、料号长短排好，最多 limit 条
     */
    List<PartMatch> matchParts(String keyword, int limit);

    /** @param match EXACT / PREFIX / CONTAINS */
    record PartMatch(String partNo, String match) {
    }
}
