package ai.neargo.shop.pay.api.guard.core;

import java.net.InetAddress;

/**
 * IP / CIDR 白名单：逗号分隔，单个地址或网段（IPv4 / IPv6）。开放 API 接入方的 {@code ip_whitelist}
 * 与内部入口的 {@code shop.pay.guard.internal.allowed-ips}（开发计划 M2.8）共用这一份实现。
 *
 * <p>只认字面 IP，<b>不解析主机名</b>（解析会带来 DNS 查询，也让白名单可被 DNS 改写）。
 * 地址按字节比，所以 {@code ::1} 与 Tomcat 给的 {@code 0:0:0:0:0:0:0:1} 是同一个。
 */
public final class IpAllowList {

    private IpAllowList() {
    }

    /** 名单为空 = 不限；地址为空、写坏的条目不放行 */
    public static boolean allows(String list, String ip) {
        if (list == null || list.isBlank()) {
            return true;
        }
        byte[] addr = literal(ip);
        if (addr == null) {
            return false;
        }
        for (String entry : list.split(",")) {
            String e = entry.trim();
            if (!e.isEmpty() && matches(e, addr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String entry, byte[] addr) {
        int slash = entry.indexOf('/');
        byte[] net = literal(slash < 0 ? entry : entry.substring(0, slash));
        if (net == null || net.length != addr.length) {
            return false;
        }
        int bits;
        try {
            bits = slash < 0 ? net.length * 8 : Integer.parseInt(entry.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (bits < 0 || bits > net.length * 8) {
            return false;
        }
        for (int i = 0; i < net.length && bits > 0; i++, bits -= 8) {
            int mask = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
            if ((net[i] & mask) != (addr[i] & mask)) {
                return false;
            }
        }
        return true;
    }

    /** 字面 IP 的字节；不是字面 IP（含主机名）返回空 */
    private static byte[] literal(String s) {
        if (s == null || s.isBlank() || !s.trim().matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(s.trim()).getAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
