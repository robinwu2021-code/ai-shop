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

    /**
     * @param channel 通道（SMS/MAIL/WXSUB/PUSH），空=不筛
     * @param status  SENT/FAILED，空=不筛
     * @param bizType 用途（{@code NotifyBizType}），空=不筛。
     *                <b>与 channel 正交</b>：同一条短信通道上既有验证码也有交易触达，
     *                只按通道筛答不了「今天的验证码发得怎么样」
     * @param from    起始日（含），空=不限。排查永远是「今天出的事」，
     *                而这张表是<b>只增不删</b>的（发送记录长期保留），按通道翻页找当天的很快就翻不动
     * @param to      截止日（<b>含当天</b>，实现里按次日零点开区间），空=不限
     * @param target  收件人。<b>库里存的是掩码后的值</b>（{@code 138****8000} / {@code r***n@neargo.ai}），
     *                所以输入要先按同一口径掩码再匹配 —— 直接拿明文查会一条都查不到，
     *                而那正是运营手上唯一有的东西。输入片段（如尾四位）则原样模糊匹配
     */
    PageData<SysNotifyLog> list(String channel, String provider, String status, String bizType,
                                String from, String to, String target, long page, long size);

    /**
     * 模拟发送前的收件人预检（TDD-运营端触达中心 §5.3/§5.4）。
     *
     * <p><b>为什么单独一个方法</b>：图形验证码是一次性的，而「这个用户没额度 / 没绑设备」
     * 是**输完 userNo 就能知道**的事。不预检的话，运营填完表、输完验证码、点了发送，
     * 才被告知「换个账号吧」—— 而那张验证码已经用掉了，得重来一遍。
     *
     * <p>可读错误在这里抛（{@code NOTIFY_WX_QUOTA_EMPTY} / {@code NOTIFY_NO_DEVICE}）；
     * 短信与邮件没有可预检的东西，直接放行。
     *
     * @param target WXSUB/PUSH 传 userNo；其余通道忽略
     */
    void precheckTestTarget(String channel, String target, String scene);

    /**
     * 站内信的模拟发送（TDD-运营端触达中心 §5.5）。
     *
     * <p><b>形态与外发通道不同</b>：不进 {@code sys_notify_log}（站内信自己就是一张可查的表），
     * 也不需要图形验证码 —— 它发不出平台，骚扰不到任何外部的人。权限码与审计仍然要。
     *
     * @param receiverType {@code MsgMessage.RECEIVER_*}：USER=消费者 / STAFF=商家员工 / OPS=运营
     */
    void testInApp(String receiverType, String receiverNo, String title, String body,
                   String link, String operatorNo);

    /**
     * 测试发送。**只发得出去，读不回来** —— 不返回验证码内容，
     * 否则这个接口就成了「给任意手机号发一个我知道的验证码」，那正是它要防的事。
     *
     * @param target    <b>随通道而变</b>：SMS=手机号 / MAIL=邮箱 /
     *                  WXSUB、PUSH=<b>userNo</b>（运营手上没有 openid 与 cid，
     *                  而这两个也不该出现在运营界面上）
     * @param level     仅 PUSH 用：{@code RING} 走响铃级。B 端新订单就是这个形态，
     *                  上线前必须能在真机上验一次它到底响不响
     * @param content   运营在抽屉里填的自定义内容（{@code null} 表示全用默认值）。
     *                  <b>各通道能填多少是通道定的</b>，见 {@link TestContent}
     * @param captchaId 图形验证码挑战 ID
     * @param code      用户输入的图形验证码
     */
    void testSend(String channel, String target, String level, TestContent content,
                  String clientId, String captchaId, String code, String operatorNo);

    /**
     * 某收件人（userNo）绑定的**推送终端设备**列表，供运营端「选择终端发起测试」。
     * 同时含 USER 与 STAFF 两个收件箱 —— 一个人可能只在其中一端登录过 App。
     */
    java.util.List<PushDeviceVO> pushDevices(String userNo);

    /**
     * @param clientId    设备标识（个推 cid / FCM/APNs token）。运营授权可见，用于定向发送
     * @param clientIdMask 展示用掩码
     */
    record PushDeviceVO(String receiverType, String platform, String provider,
                        String clientId, String clientIdMask, String updatedAt) {
    }

    /**
     * 模拟发送的自定义内容。**一个通道只认其中几项**，其余忽略：
     *
     * <ul>
     *   <li>SMS —— 只认 {@code params}（阿里云收的是模板号+参数，不收自由文本；
     *       发未报备的内容会被直接拒）。{@code subject/body} 填了也没用</li>
     *   <li>MAIL —— {@code subject} + {@code body}，全自由</li>
     *   <li>WXSUB —— {@code params}（{@code thing2} 提示语等，微信模板里的可变字段）</li>
     *   <li>PUSH —— {@code subject} 当标题、{@code body} 当正文</li>
     * </ul>
     *
     * @param params 模板参数。键名与模板占位一致（短信 {@code code}、微信 {@code thing2}…）
     */
    record TestContent(String subject, String body, java.util.Map<String, String> params) {

        /** 正文长度上限。可填之后要有个上限，否则这里就成了群发工具 */
        public static final int MAX_BODY = 2000;

        public String paramOr(String key, String fallback) {
            String v = params == null ? null : params.get(key);
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        public String subjectOr(String fallback) {
            return subject == null || subject.isBlank() ? fallback : subject.trim();
        }

        public String bodyOr(String fallback) {
            return body == null || body.isBlank() ? fallback : body.trim();
        }
    }
}
