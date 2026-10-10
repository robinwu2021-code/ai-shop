package ai.neargo.shop.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 文案门面：错误码 → 本地化文案（zh-CN / en / ar，回落链见 [矩阵 C-17.2]）。
 *
 * <p>静态持有 {@link MessageSource} 是刻意的：异常处理与领域层都要用，
 * 若走注入，每个抛异常的地方都得拿到 bean —— 那会逼着领域层依赖 Spring。
 */
@Component
public class Messages {

    private static MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        Messages.messageSource = messageSource;
    }

    /** 取不到 key 时返回 key 本身：宁可前端看到 {@code err.xxx}，也不要静默吞成空串。 */
    public static String get(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
