package ai.neargo.shop.marketing.slot.dto;

import java.util.List;

/** 内容位的对外形状。 */
public final class SlotVOs {

    private SlotVOs() {
    }

    /**
     * 运营端看到的内容位。
     *
     * <p>时间用 <b>ISO 串</b>而不是毫秒：运营端的排期表单收发的就是 ISO
     * （{@code setSlotSchedule(onlineAt, offlineAt)}），库里存毫秒是后端自己的事。
     * 两边各用各的习惯、只在这一层换算，好过让其中一边迁就另一边。
     *
     * @param goodsNos <b>有序</b>；HOME_FLOOR 的内容就是它，其余形态恒为空
     * @param archivedAt 归档时间（毫秒）；null = 在用
     */
    public record ContentSlotVO(String slotNo,
                                String title,
                                String kind,
                                int sort,
                                List<String> communityNos,
                                List<String> goodsNos,
                                String onlineAt,
                                String offlineAt,
                                boolean enabled,
                                Long archivedAt) {
    }

    /**
     * 建 / 改内容位。{@code slotNo} 为空 = 新建（与建券同一个约定）。
     *
     * @param sort 同 kind 内顺序，小的在前；null 按 0
     */
    public record SlotSaveCmd(String slotNo,
                              String title,
                              String kind,
                              Integer sort,
                              List<String> communityNos,
                              List<String> goodsNos,
                              String onlineAt,
                              String offlineAt,
                              Boolean enabled) {
    }
}
