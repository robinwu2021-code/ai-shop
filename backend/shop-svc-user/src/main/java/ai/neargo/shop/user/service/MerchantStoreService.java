package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.StoreProfileVO;

import java.util.List;

/**
 * 店铺资料（B-11.2）。商家自己维护的门面 + 经营范围。
 *
 * <p>它横跨两张表（门面在 {@code mch_store}，范围在 {@code mch_entity}
 * 与 {@code mch_entity_community}），对前端是一份资料。拆表的理由见 V30 迁移。
 */
public interface MerchantStoreService {

    /** 读；从没保存过时返回各字段为空的默认资料，<b>不是 null</b> —— 新店打开设置页应当看到空表单而不是报错。 */
    StoreProfileVO profile(String merchantNo);

    /**
     * 保存。
     *
     * <p><b>scope=COMMUNITY 且覆盖社区为空时拒绝保存</b>（ADR-009）。
     * 这条规则在入驻审核那边也有一份，两处必须一致 —— 否则商家可以入驻时配好范围，
     * 转头在店铺设置里把社区清空，然后货就对谁都不可见了，而这中间没有任何提示。
     */
    StoreProfileVO save(String merchantNo, SaveCommand cmd);

    /**
     * 覆盖社区全量替换。审核与店铺设置<b>共用这一处实现</b> ——
     * 「空覆盖 = 对谁都不可见」这条规则只有一份代码，才不会两条路径各写各的。
     */
    void syncCommunities(String merchantNo, List<String> communityNos);

    record SaveCommand(String announcement, String openHours, String address,
                       List<String> featured, String serviceScope,
                       List<String> serviceCommunityNos, String serviceCityCode) {
    }
}
