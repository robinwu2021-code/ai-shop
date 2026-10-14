package ai.neargo.shop.spi.notify;

import java.util.Map;

/**
 * 域 → message：按**平台业务模板**发一封邮件。
 *
 * <p><b>与 {@link SmsPort} / {@link WxSubscribePort} 的模板不是一回事</b>：
 * 那两条通道的模板由通道方报备，我们只能填参数；邮件的模板是我们自己定义的，
 * 库里那份就是发出去的那份。所以这个 Port 收的是 <b>模板号 + 参数</b>，
 * 而模板正文在 {@code notify_template} 里，运营端可看可改。
 *
 * <p>放在 spi 是因为调用方在 platform 域（建运营账号、重置密码），
 * 而模板与渲染在 message 域 —— 两个域之间只能经 Port。
 *
 * <p><b>模板缺失或被停用时回落内置默认文案</b>，不抛：
 * 这两封是账号类邮件，发不出的后果是「新同事永远登不进来」，
 * 比「文案没跟上最新一版」严重得多。回落时记 WARN。
 */
public interface MailTemplatePort {

    /**
     * 平台默认语言的设置键（{@code sys_setting}）。
     *
     * <p><b>它答的是「收件人语言未知时按哪种发」</b>，不是「邮件用哪种语言」——
     * 知道收件人语言时（本人发起的请求）一律用他自己的，与这个设置无关。
     * 目前唯一用到它的是「管理员替别人建账号」：那封信的收件人还没登录过，
     * 平台对他一无所知。
     *
     * <p>做成系统统一设置而不是按人存：一个平台的对外默认语言是**一件事**，
     * 存在人身上就变成 N 件事，而那 N 份值第一次写入时全都是猜的。
     */
    String DEFAULT_LANG_SETTING_KEY = "notify.default-lang";

    /** 运营账号开通。参数：{@code realName} / {@code username} / {@code password}。 */
    String TPL_OPS_INIT_PWD = "TPL_MAIL_OPS_INIT_PWD";

    /** 运营密码重置。参数：{@code realName} / {@code token} / {@code ttlMinutes}。 */
    String TPL_OPS_RESET_PWD = "TPL_MAIL_OPS_RESET_PWD";

    /**
     * @param to             收件邮箱
     * @param templateNo     {@code notify_template.template_no}
     * @param subject        邮件主题。**不进模板**：主题短、且与正文的可变部分无关，
     *                       为它再加一层模板只是多一处要对齐的地方
     * @param params         占位参数。**缺参数时按内置默认发**，不发一封写着
     *                       {@code {password}} 的邮件给用户
     * @param defaultContent 模板缺失/停用时用的内置文案（与模板正文保持一致）
     * @param bizType        {@link NotifyBizType}，进发送记录
     */
    /**
     * @param lang 收件人的语言（{@code zh-CN} / {@code en} / {@code ar}）。
     *             <b>传 {@code null} 表示「不知道收件人的语言」</b> ——
     *             此时按平台默认语言发（{@link #DEFAULT_LANG_SETTING_KEY}）。
     *             这不是「随便给个默认值」：调用方明确知道自己不知道，
     *             而由谁来决定默认是平台的事，不是每个调用点各猜一个。
     *             <b>取不到那种语言的翻译就回落 zh-CN</b>，再取不到才用内置文案 ——
     *             一封英文用户读不懂的中文邮件，仍然好过一封发不出去的邮件。
     *             <p><b>传谁的语言要想清楚</b>：只有「请求人 == 收件人」时
     *             才能用请求的 {@code Accept-Language}。管理员替别人建账号那种，
     *             用请求语言等于按管理员的偏好给新同事发信
     */
    void send(String to, String templateNo, String lang, String subject,
              Map<String, String> params, String defaultContent,
              String bizType, String operatorNo);
}
