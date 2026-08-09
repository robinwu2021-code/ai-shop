package ai.neargo.shop.user.dto;

import ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief;

/**
 * 「我的常去店」列表项（对齐 c-app {@code MerchantBrief}）。
 *
 * <p>字段与 merchant 域的 {@code MerchantVO.Brief} 一致，**这是有意的重复**。
 * 收藏列表要显示店名与 logo，但收藏本身（{@code usr_store_favorite}）是用户数据。
 * 让用户域直接返回商家域的 DTO，等于把商家的展示结构焊死在用户接口上——
 * 商家域给 Brief 加一个字段，用户接口的响应就跟着变，而没有人做过这个决定。
 *
 * <p>这里付出的是一次字段映射；换回来的是两边可以各自演进。
 */
public record StoreBriefVO(String merchantNo, String name, String logo,
                           double rating, boolean verified, int breachCount) {

    public static StoreBriefVO of(MerchantBrief b) {
        return new StoreBriefVO(b.merchantNo(), b.merchantName(), b.logo(),
                b.rating(), b.verified(), b.breachCount());
    }
}
