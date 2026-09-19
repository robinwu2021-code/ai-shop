package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 触达批次头：发出去的每一次。「发出去的」列表与效果页读它。
 *
 * <p>三个效果计数<b>只由回写带条件原子累加</b>（明细那行从空变成有值、影响 1 行时才 +1）——
 * 列表不扫明细，重复进店、重复回调也不会多算。
 */
@Getter
@Setter
@TableName("mbr_reach_task")
public class MbrReachTask extends BaseEntity {

    private String taskNo;
    private String entityNo;
    private String scene;
    private String title;
    private String body;
    /** 发送那一刻的受众项。人群后来改了条件，这里仍是当时发给了谁 */
    private String audienceJson;
    private String audienceDesc;
    private Integer matchedCount;
    /** 进了买家小程序消息列表的人数（批 D 起；之前是推送成功数） */
    private Integer sentCount;
    /** 其中推送到手机的人数 */
    private Integer pushedCount;
    private Integer skippedCount;
    private String skipDetail;
    private Integer openedCount;
    private Integer orderedCount;
    private Long orderedAmountMinor;
    private Long sentAt;
    /** 过了这个时刻即「已统计」：归因窗口关了，数字不会再变 */
    private Long statsUntil;
    private String operatorNo;
}
