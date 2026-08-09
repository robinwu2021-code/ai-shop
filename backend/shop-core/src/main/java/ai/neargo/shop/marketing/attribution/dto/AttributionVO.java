package ai.neargo.shop.marketing.attribution.dto;

/**
 * 归因结果。
 *
 * @param trafficSource 由 {@code source} 推出：只有店铺码归因算商家自带客流。
 *                      邀请人带来的客户在别家消费才是平台的收益（ADR-004 §6），
 *                      因此不能算成商家自带
 */
public record AttributionVO(String merchantNo,
                            String inviterNo,
                            String channel,
                            String source,
                            String trafficSource,
                            long expireAt) {
}
