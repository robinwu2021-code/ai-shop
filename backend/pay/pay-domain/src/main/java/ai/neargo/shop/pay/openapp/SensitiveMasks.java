package ai.neargo.shop.pay.openapp;

/**
 * 敏感字段的掩码（开发计划 M3.5；API 清单 §2.2）：查询只回掩码，不回密文、不回明文。
 * 号码类留前 4 后 4（{@code 8123****4567}）；邮箱留首字与域名；姓名类留首字。
 */
public final class SensitiveMasks {

    private SensitiveMasks() {
    }

    /** @param field 字段名（路径的最后一段：phone / email / name / bankCode / accountNo / accountName） */
    public static String mask(String field, String value) {
        if (value == null) {
            return null;
        }
        if ("email".equals(field)) {
            int at = value.indexOf('@');
            return at <= 0 ? stars(value.length()) : value.charAt(0) + "****" + value.substring(at);
        }
        if (field.endsWith("name") || field.endsWith("Name")) {
            return value.isEmpty() ? "" : value.substring(0, value.offsetByCodePoints(0, 1))
                    + stars(value.codePointCount(0, value.length()) - 1);
        }
        int n = value.length();
        if (n >= 8) {
            return value.substring(0, 4) + "****" + value.substring(n - 4);
        }
        if (n >= 3) {
            return value.charAt(0) + stars(n - 2) + value.charAt(n - 1);
        }
        return stars(n);
    }

    private static String stars(int n) {
        return "*".repeat(Math.max(0, n));
    }
}
