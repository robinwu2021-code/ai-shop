package ai.neargo.shop.link;

import ai.neargo.shop.link.entity.ShortLink;
import ai.neargo.shop.link.mapper.ShortLinkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 短链服务的承诺（TDD-收件人物流触达 §4.2）：
 * 建码返回完整短链、撞码重取、解码查目标并点击 +1、过期/查无一律空。
 */
@DisplayName("短链服务 ShortLinkService")
class ShortLinkServiceTest {

    private final ShortLinkMapper mapper = mock(ShortLinkMapper.class);
    private final ShortLinkService service = new ShortLinkService(mapper, "https://s.hxmall.top");

    @Test
    @DisplayName("建码返回 base + / + 7 位短码")
    void shortenReturnsFullUrl() {
        when(mapper.insert(any(ShortLink.class))).thenReturn(1);
        String url = service.shorten("https://wxaurl.cn/x", ShortLink.BIZ_SHIP_TRACK, "SUB-A", null);
        assertThat(url).startsWith("https://s.hxmall.top/");
        String code = url.substring("https://s.hxmall.top/".length());
        assertThat(code).hasSize(7);
        // 阿里云短信链接变量：值首字母须是字母、≤8 位（见 BizKey.shortCode）。数字开头会被拒
        assertThat(Character.isLetter(code.charAt(0))).as("短码首字母必须是字母").isTrue();
    }

    @Test
    @DisplayName("尾斜杠归一 —— base 带不带 / 结果一样")
    void baseTrailingSlashNormalized() {
        ShortLinkService s2 = new ShortLinkService(mapper, "https://s.hxmall.top/");
        when(mapper.insert(any(ShortLink.class))).thenReturn(1);
        assertThat(s2.shorten("t", "B", "r", null)).doesNotContain("top//");
    }

    @Test
    @DisplayName("★ 撞码 → 换码重取；第二次成功就返回")
    void retriesOnCollision() {
        when(mapper.insert(any(ShortLink.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(1);
        String url = service.shorten("t", "B", "r", null);
        assertThat(url).startsWith("https://s.hxmall.top/");
        verify(mapper, times(2)).insert(any(ShortLink.class));
    }

    @Test
    @DisplayName("连撞到上限 → 抛，不静默返回一个没插进去的码")
    void throwsAfterMaxRetry() {
        when(mapper.insert(any(ShortLink.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.shorten("t", "B", "r", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ 解码查到且没过期 → 返回 target 并 hits+1")
    void resolveReturnsTargetAndBumps() {
        ShortLink row = new ShortLink();
        row.setId(9L);
        row.setTarget("https://wxaurl.cn/x");
        when(mapper.selectOne(any())).thenReturn(row);
        assertThat(service.resolve("ABC1234")).contains("https://wxaurl.cn/x");
        verify(mapper).bumpHits(9L);
    }

    @Test
    @DisplayName("★ 已过期 → 空，且不计点击")
    void resolveExpiredIsEmpty() {
        ShortLink row = new ShortLink();
        row.setId(9L);
        row.setTarget("t");
        row.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(mapper.selectOne(any())).thenReturn(row);
        assertThat(service.resolve("ABC1234")).isEmpty();
        verify(mapper, never()).bumpHits(any());
    }

    @Test
    @DisplayName("查无此码 / 空码 → 空")
    void resolveMissingIsEmpty() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolve("NOPE")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }
}
