package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.merchant.entity.MchEntity;

/**
 * 商家<b>账号</b>视图 —— 与 {@link MerchantVO} 是两件事，刻意不合并。
 *
 * <p>{@code MerchantVO} 是**给买家看的店**：评分、销量、认证标、标签。
 * 这里是**给店主看的自己**：我是什么主体、我现在什么状态、还能不能上架。
 * 两者唯一重合的是店名和 logo。
 *
 * <p>合并的代价是 {@code status} 会跟着买家侧的查询一起被查出去 ——
 * 而「这家店被封了」不该出现在任何 C 端响应里。
 */
public record MerchantAccountVO(String merchantNo, String name, String logo,
                                String subject, String tier, String status,
                                String industry, String description) {

    public static MerchantAccountVO of(MchEntity m) {
        return new MerchantAccountVO(m.getEntityNo(), m.getName(), m.getLogo(),
                m.getLegalForm(), m.getTier() == null ? "SMALL" : m.getTier(), m.getStatus(),
                m.getIndustry(), m.getDescription());
    }
}
