package ai.neargo.shop.marketing.slot;

import ai.neargo.shop.marketing.slot.dto.SlotVOs.ContentSlotVO;
import ai.neargo.shop.marketing.slot.dto.SlotVOs.SlotSaveCmd;

import java.util.List;

/**
 * 内容位：运营配的首页楼层 / 轮播 / 频道。
 *
 * <p><b>这一版只有 HOME_FLOOR 被端消费</b>，理由见
 * {@link ai.neargo.shop.marketing.slot.entity.MktContentSlot}。
 */
public interface ContentSlotService {

    /*
     * 这几个方法**不收 operatorNo**：谁改的由两处各自记 ——
     * 库里是 BaseEntity 的 updated_by（自动填充），留痕是控制器里的 AuditLogPort。
     * 再收一个参数只会多一个可以传错的地方，而且传错了没有任何东西会发现。
     */

    /**
     * 运营端列表。
     *
     * @param kind        为空给全部
     * @param enabled     为空给全部；true/false 按开关筛
     * @param showArchived false 时不含已归档的
     */
    List<ContentSlotVO> opsSlots(String kind, Boolean enabled, String keyword, boolean showArchived);

    /**
     * 建 / 改。硬校验三条：下线必须晚于上线、HOME_FLOOR 必须有商品、商品必须真的存在。
     *
     * <p>第三条不是洁癖：货号打错一位不会有任何报错，那一格在首页上**静默消失**，
     * 而运营看着自己刚保存的配置，以为它在生效。
     */
    ContentSlotVO saveSlot(SlotSaveCmd cmd);

    /** 开 / 关。**即刻生效**，不等下线时间 —— 出了问题运营要的是「现在就下」。 */
    ContentSlotVO setEnabled(String slotNo, boolean enabled);

    /** 改排期。下线必须晚于上线。 */
    ContentSlotVO setSchedule(String slotNo, String onlineAt, String offlineAt);
}
