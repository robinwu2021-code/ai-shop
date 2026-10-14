package ai.neargo.shop.trade.order.service.ship;

/**
 * 运单号规范化（订单服务 2.0 · 发货容错）。店主常从短信/OCR/聊天里粘贴，带空格、换行、
 * 全角、中文前缀（"单号："）、大小写不一。入库前统一清洗成纯净单号，避免"输错一位 →
 * 订阅成功却永远无轨迹"这类静默失败。
 *
 * <p>只做无损清洗：全角转半角、去所有空白、去中文、去冒号、转大写；<b>保留字母/数字/连字符</b>
 * （少数承运商单号含 {@code -}）。格式是否合法由 {@code WaybillFormat} 另判。
 */
public final class WaybillNormalizer {

    private WaybillNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            char h = toHalfWidth(c);
            if (Character.isWhitespace(h)) {
                continue;                      // 去空白（含换行/制表/全角空格）
            }
            if (h >= '一' && h <= '龥') {
                continue;                      // 去中文
            }
            if (h == ':' || h == '：') {
                continue;                      // 去冒号（半/全角）
            }
            b.append(Character.toUpperCase(h));
        }
        return b.toString();
    }

    private static char toHalfWidth(char c) {
        if (c == '　') {
            return ' ';                        // 全角空格
        }
        if (c >= '！' && c <= '～') {
            return (char) (c - 0xfee0);        // 全角 ASCII → 半角
        }
        return c;
    }
}
