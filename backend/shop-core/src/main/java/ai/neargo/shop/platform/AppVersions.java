package ai.neargo.shop.platform;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号比较（TDD-商户App问题回报与版本 V4）。
 *
 * <p><b>只取开头的 {@code 数字(.数字){0,2}}</b>，缺的段按 0，之后的内容（{@code +812}、空格、括号里的构建号）一律丢掉。
 * App 的版本号来自编译参数 {@code APP_VERSION}，形如 {@code 1.4.2 (20261108.1)}；App 自己的 {@code compareVersions}
 * 按 {@code .} 硬拆，第三段拆成 {@code 2 (20261108}、解析失败按 0，于是把 1.4.2 当成 1.4.0。
 * 服务端照抄就会连这个 bug 一起抄 —— 而 10426 拒写用的正是这里的比较，两边差一点，
 * 就会出现「服务端拦了写、App 却判断不用升级」。
 */
public final class AppVersions {

    private static final Pattern PREFIX = Pattern.compile("^\\s*(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?");

    private AppVersions() {
    }

    /** 能否取出版本前缀。空串、null、不以数字开头的都不能。 */
    public static boolean isVersion(String v) {
        return parse(v) != null;
    }

    /**
     * 比较两个版本。
     *
     * @return 负数 a 更旧、0 相同、正数 a 更新
     * @throws IllegalArgumentException 任一方取不出版本前缀 —— 调用方先用 {@link #isVersion} 判
     */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) {
            throw new IllegalArgumentException("not a version: " + (x == null ? a : b));
        }
        for (int i = 0; i < 3; i++) {
            int d = Integer.compare(x[i], y[i]);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = PREFIX.matcher(v);
        if (!m.find()) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            String g = m.group(i + 1);
            out[i] = g == null ? 0 : Integer.parseInt(g);
        }
        return out;
    }
}
