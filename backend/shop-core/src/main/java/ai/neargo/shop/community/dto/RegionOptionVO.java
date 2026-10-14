package ai.neargo.shop.community.dto;

/**
 * 一个「可选的区域」——<b>有已开通社区的那种</b>。
 *
 * <p>区划全表有 2978 个区县、41352 个街道。把它整棵扔给用户去挑，
 * 十有八九挑到一个一家店都没有的区：那不是选区域，那是抽奖。
 * 所以这里只出现真的有货可买的地方，并且带上 {@link #communityCount} ——
 * 「西湖区 · 2 个小区」比光秃秃一个区名有用得多。
 *
 * @param regionCode    区县级国标码（6 位）。社区可能挂在街道级，聚合时截到区县
 * @param name          区县名，如「西湖区」
 * @param cityCode      所属市码（4 位）
 * @param cityName      所属市名，如「杭州市」。同名区县全国有很多（如「城关区」），
 *                      不带市名的话用户分不清是哪一个
 * @param communityCount 该区县下已开通的社区数
 */
public record RegionOptionVO(String regionCode,
                             String name,
                             String cityCode,
                             String cityName,
                             int communityCount) {
}
