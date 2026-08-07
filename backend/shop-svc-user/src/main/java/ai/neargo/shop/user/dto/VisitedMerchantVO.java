package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.entity.UsrMerchant;

/** 我买过的商家（对齐 c-app {@code VisitedMerchant}）。 */
public record VisitedMerchantVO(String merchantNo,
                                String name,
                                String logo,
                                double rating,
                                boolean verified,
                                int breachCount,
                                int orderCount,
                                long lastOrderAt) {

    public static VisitedMerchantVO of(UsrMerchant m, int orderCount, long lastOrderAt) {
        return new VisitedMerchantVO(m.getMerchantNo(), m.getName(), m.getLogo(),
                m.getRating() == null ? 0d : m.getRating() / 10d,
                Boolean.TRUE.equals(m.getVerified()),
                m.getBreachCount() == null ? 0 : m.getBreachCount(),
                orderCount, lastOrderAt);
    }
}
