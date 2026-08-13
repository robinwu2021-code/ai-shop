package ai.neargo.shop.message.notify;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.SysNotifyLog;

/**
 * 发送记录的查询，以及运营端的**测试发送**。
 *
 * <p><b>测试发送为什么要三道闸一起上</b>（权限码 + 图形验证码 + 限流）：
 * 它是一个能**指定任意收件人**的接口。只上权限码的话，运营账号泄漏就等于
 * 拿到一台群发机 —— 而且发出去的是带平台签名的正规短信，比垃圾短信更能骗到人。
 *
 * <ul>
 *   <li>权限码防越权（别人打不开这个页面）</li>
 *   <li><b>图形验证码防脚本化</b> —— 账号泄漏后攻击者拿到的是 token，而验证码要人眼</li>
 *   <li>限流防「一个人手工点很多次」</li>
 * </ul>
 */
public interface NotifyLogService {

    /** @param channel/status 传 null 表示不筛 */
    PageData<SysNotifyLog> list(String channel, String status, long page, long size);

    /**
     * 测试发送。**只发得出去，读不回来** —— 不返回验证码内容，
     * 否则这个接口就成了「给任意手机号发一个我知道的验证码」，那正是它要防的事。
     *
     * @param target    手机号或邮箱
     * @param captchaId 图形验证码挑战 ID
     * @param code      用户输入的图形验证码
     */
    void testSend(String channel, String target, String captchaId, String code, String operatorNo);
}
