package ai.neargo.shop.common;

/** 收件人称呼：只采用用户选择或历史姓名中的显式称呼，不推断性别。 */
public final class RecipientSalutation {
    public static final String MR = "MR";
    public static final String MS = "MS";
    public static final String UNSPECIFIED = "UNSPECIFIED";

    private RecipientSalutation() { }

    /** 存量空值归一为未选择。 */
    public static String normalize(String value) {
        return value == null ? UNSPECIFIED : value;
    }

    /** 供短信等中文通知使用；姓名字段本身保持原样。 */
    public static String format(String name, String salutation) {
        String clean = name == null || name.isBlank() ? "收件人" : name.trim();
        String existing = null;
        for (String suffix : new String[]{"先生/女士", "先生", "女士"}) {
            if (clean.endsWith(suffix)) {
                existing = suffix;
                clean = clean.substring(0, clean.length() - suffix.length()).trim();
                break;
            }
        }
        String suffix = switch (normalize(salutation)) {
            case MR -> "先生";
            case MS -> "女士";
            default -> existing == null ? "先生/女士" : existing;
        };
        return (clean.isEmpty() ? "收件人" : clean) + suffix;
    }
}
