package ai.neargo.shop.message.notify;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.NotifyPushTask;

import java.time.LocalDateTime;

/**
 * 平台营销广播推送任务（设计：触达推送中台-模块抽象 · N6）。
 *
 * <p>运营主动发起的一次群发：圈人群、预估触达、定时下发。到点由
 * {@code NotifyPushTaskJob}（worker）捡起 {@link #dispatchDue()}。
 *
 * <p><b>与事件驱动触达分开</b>：那个是「系统必须告诉你」（钱扣了/货到了，站内信必达），
 * 这个是「平台想推给你」（营销，opt-in —— 只发给装了 App、有推送令牌的人）。
 *
 * <p><b>为什么是接口</b>：同目录的 {@code NotifyChannelService} / {@code NotifyLogService}
 * 早就是「接口 + impl/」，这个类此前是具体类 —— `ArchitectureTest.serviceMustBeInterface`
 * 一直红着报它。补齐的是同一套约定，不是新立规矩。
 */
public interface NotifyPushTaskService {

    /** 建一个任务（草稿或定时）。 */
    NotifyPushTask create(String name, String audienceType, String title, String body,
                          String link, LocalDateTime scheduledAt, String operator);

    /** 分页列出任务。 */
    PageData<NotifyPushTask> list(String status, long page, long size);

    /** 取消一个还没下发的任务。 */
    NotifyPushTask cancel(String taskNo, String operator);

    /** worker 入口：把到点的任务发出去，返回处理条数。 */
    int dispatchDue();

    /** 预估这个人群能触达多少台设备 —— 发之前让运营看得见量级。 */
    int estimate(String audienceType);
}
