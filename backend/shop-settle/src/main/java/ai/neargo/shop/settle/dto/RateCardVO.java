package ai.neargo.shop.settle.dto;

/**
 * 费率说明（B-11.9.5 / R16）。
 *
 * <p>把费率明明白白告诉商家，是「自带客流零佣金」这个策略能起作用的前提 ——
 * 商家算不清楚自己能拿多少，就不会有动力把老客带进来。
 */
public record RateCardVO(int merchantOwnedRate, int platformRate, String note) {
}
