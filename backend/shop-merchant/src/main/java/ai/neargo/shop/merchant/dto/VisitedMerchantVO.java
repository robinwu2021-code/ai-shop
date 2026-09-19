package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.merchant.entity.MchEntity;

/** 我买过的商家（对齐 c-app {@code VisitedMerchant}）。 */
public record VisitedMerchantVO(String merchantNo,
                                String name,
                                String logo,
                                double rating,
                                boolean verified,
                                int breachCount,
                                int orderCount,
                                long lastOrderAt,
                                /* 自营（电商法 §37）。店铺页「我买过的」那一档用它出「自营」标与品牌头像 ——
                                   其余两档走 MerchantVO 早就有，只有这一份漏了，真机上虹选鲜果在这一档显示成一个「虹」字 */
                                boolean selfOperated) {

    public static VisitedMerchantVO of(MchEntity m, int orderCount, long lastOrderAt) {
        return new VisitedMerchantVO(m.getEntityNo(), m.getName(), m.getLogo(),
                m.getRating() == null ? 0d : m.getRating() / 10d,
                Boolean.TRUE.equals(m.getVerified()),
                m.getBreachCount() == null ? 0 : m.getBreachCount(),
                orderCount, lastOrderAt,
                Integer.valueOf(1).equals(m.getSelfOperated()));
    }
}
