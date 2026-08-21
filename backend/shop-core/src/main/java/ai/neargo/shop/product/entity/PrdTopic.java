package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 主题分类（陈列）。
 *
 * <p><b>与类目正交</b>：一件豆浆同时属于类目「预包装食品」和主题「早餐必备」——
 * 类目回答「这是什么货、要什么资质」，主题回答「这周首页摆什么」。
 *
 * <p><b>与营销活动分开</b>：运营做「早餐必备」时往往只是想把这 20 件摆到一起，
 * 并不想降价。合并的结果是他为了摆个专题被迫建一个 0 折扣的活动，
 * 而活动列表从此再也读不懂。
 */
@Getter
@Setter
@TableName("prd_topic")
public class PrdTopic extends BaseEntity {

    private String topicNo;
    private String title;
    /** 一句话说明，如「7 点前送到」。空 = 不展示副标题 */
    private String subtitle;
    private String cover;
    private Integer sort;
    /**
     * 生效起止。<b>都可空</b> —— 常设专题（「本地时令」）没有档期，
     * 强制填一个假的结束时间会让它某天悄悄消失，而没人记得自己填过。
     */
    private java.time.LocalDateTime startAt;
    private java.time.LocalDateTime endAt;
    /** ACTIVE / ARCHIVED。**归档不删**：C 端历史链接还指着它 */
    private String status;
}
