package ai.neargo.shop.pay.api.guard.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IP / CIDR 白名单（开放 API 接入方的 {@code ip_whitelist} 与内部入口的 {@code allowed-ips} 共用，开发计划 M2.8）。
 */
class IpAllowListTest {

    @Test
    @DisplayName("★★★ IPv4 网段与单个地址")
    void ipv4() {
        assertThat(IpAllowList.allows("10.0.0.0/8", "10.0.1.12")).isTrue();
        assertThat(IpAllowList.allows("10.0.0.0/8", "11.0.0.1")).isFalse();
        assertThat(IpAllowList.allows("192.168.1.0/25", "192.168.1.200")).isFalse();
        assertThat(IpAllowList.allows("203.0.113.5", "203.0.113.5")).isTrue();
        assertThat(IpAllowList.allows("203.0.113.5, 10.0.0.0/8", "10.9.9.9")).isTrue();
    }

    @Test
    @DisplayName("★★★ IPv6：回环有两种写法（::1 与 Tomcat 给的 0:0:0:0:0:0:0:1），都认")
    void ipv6() {
        assertThat(IpAllowList.allows("::1/128", "0:0:0:0:0:0:0:1")).isTrue();
        assertThat(IpAllowList.allows("::1", "0:0:0:0:0:0:0:1")).isTrue();
        assertThat(IpAllowList.allows("fd00::/8", "fd12:3456::1")).isTrue();
        assertThat(IpAllowList.allows("127.0.0.1/32", "::1")).as("v4 规则不匹配 v6 地址").isFalse();
    }

    @Test
    @DisplayName("★★ 白名单为空 = 不限；地址为空或写坏的条目不放行")
    void edges() {
        assertThat(IpAllowList.allows(null, "1.2.3.4")).isTrue();
        assertThat(IpAllowList.allows(" ", "1.2.3.4")).isTrue();
        assertThat(IpAllowList.allows("10.0.0.0/8", null)).isFalse();
        assertThat(IpAllowList.allows("not-an-ip/99", "10.0.0.1")).isFalse();
        assertThat(IpAllowList.allows("10.0.0.0/8", "evil.example.com")).as("不解析主机名").isFalse();
    }
}
