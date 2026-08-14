package ai.neargo.shop.message.notify.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.MsgTemplate;
import ai.neargo.shop.message.mapper.MessageMappers.TemplateMapper;
import ai.neargo.shop.spi.notify.MailTemplatePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MailTemplatePort} 实现：从 {@code msg_template} 取正文、代入参数、交给邮件通道。
 *
 * <p><b>为什么邮件的模板正文在库里而短信的不在</b>：短信模板由阿里云报备，
 * 库里那份只是给运营看的副本；邮件模板没有第三方，**库里这份就是发出去的那份**。
 */
@Component
public class MailTemplatePortImpl implements MailTemplatePort {

    private static final Logger log = LoggerFactory.getLogger(MailTemplatePortImpl.class);

    /** 占位形如 {@code {realName}}，与端上预览用的同一套规则（lib/notify-template.ts）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final TemplateMapper templateMapper;
    private final NotifyLoggingMailPort mailPort;

    public MailTemplatePortImpl(TemplateMapper templateMapper, NotifyLoggingMailPort mailPort) {
        this.templateMapper = templateMapper;
        this.mailPort = mailPort;
    }

    @Override
    public void send(String to, String templateNo, String lang, String subject,
                     Map<String, String> params, String defaultContent,
                     String bizType, String operatorNo) {
        String content = contentOf(templateNo, lang, defaultContent);
        mailPort.send(to, subject, render(content, params, defaultContent), bizType, operatorNo);
    }

    /**
     * 取模板正文。**缺失或停用都回落内置默认，并记 WARN**。
     *
     * <p>这两封是账号类邮件：发不出去的后果是「新同事永远登不进来」，
     * 比「文案没跟上最新一版」严重得多。所以停用一条账号模板不会拦住它 ——
     * 运营端的模板页也照这个口径写明，否则运营会以为停用能关掉它们。
     */
    private String contentOf(String templateNo, String lang, String defaultContent) {
        String want = lang == null || lang.isBlank() ? MsgTemplate.LANG_DEFAULT : lang.trim();
        MsgTemplate t = pick(templateNo, want);
        if (t == null && !MsgTemplate.LANG_DEFAULT.equals(want)) {
            /*
             * **缺译回落默认语言**，而不是回落内置文案：库里那份中文至少是
             * 运营维护中的最新文案，内置那份是发版时冻住的。
             * 一封读得懂但语言不对的邮件，好过一封内容过期的。
             */
            log.warn("[mail] 模板 {} 没有 {} 译文，回落 {}", templateNo, want, MsgTemplate.LANG_DEFAULT);
            t = pick(templateNo, MsgTemplate.LANG_DEFAULT);
        }
        if (t == null || t.getContent() == null || t.getContent().isBlank()) {
            log.warn("[mail] 模板 {} 缺失或已停用，回落内置文案 —— 账号类邮件不因此不发", templateNo);
            return defaultContent;
        }
        return t.getContent();
    }

    /** 停用的当作没有 —— 与「缺失」同一处理，回落链才只有一条。 */
    private MsgTemplate pick(String templateNo, String lang) {
        MsgTemplate t = DataScopeContext.executeWithoutScope(() ->
                templateMapper.selectOne(Wrappers.<MsgTemplate>lambdaQuery()
                        .eq(MsgTemplate::getTemplateNo, templateNo)
                        .eq(MsgTemplate::getLang, lang).last("limit 1")));
        return t == null || Boolean.FALSE.equals(t.getEnabled()) ? null : t;
    }

    /**
     * 代入参数。
     *
     * <p><b>有占位没填就整封回落内置文案</b>，而不是发一封写着 {@code {password}} 的邮件：
     * 收件人看到的是一封读不懂的邮件，而他此刻正等着这个密码登录。
     * 回落之后至少内容是完整的 —— 内置文案与模板的占位集是一致的（同一次改动里维护）。
     */
    private String render(String content, Map<String, String> params, String defaultContent) {
        Map<String, String> p = params == null ? Map.of() : params;
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            String v = p.get(m.group(1));
            if (v == null || v.isBlank()) {
                log.warn("[mail] 模板占位 {} 没有取值，整封回落内置文案 —— "
                        + "宁可少一次文案更新，也不发一封读不懂的邮件", m.group(1));
                return renderOrRaw(defaultContent, p);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 内置文案自身也可能带占位（两者占位集一致）；它再缺就只能原样发，那是代码 bug。 */
    private String renderOrRaw(String defaultContent, Map<String, String> params) {
        Matcher m = PLACEHOLDER.matcher(defaultContent);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = params.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
