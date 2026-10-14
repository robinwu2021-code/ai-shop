package ai.neargo.shop.user;

import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.impl.UserLocationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 最后已知位置的写回（L2b，TDD-虹选鲜果运营落地 §1）。
 *
 * <p>写回是异步的，这里用 Mockito 的 timeout/after 等后台线程。守三件最该守的：
 * ①该写时写、②节流内不写（否则每次定位一次写）、③<b>绝不碰主动绑定的 community_no</b>。
 */
class UserLocationServiceTest {

    private static final int LAT = 22_540_000, LNG = 114_050_000;

    private static UserLocationServiceImpl svc(UserMapper mapper) {
        return new UserLocationServiceImpl(mapper, 500, 30);   // 阈值 500m / 节流 30min
    }

    private static UsrAccount account(LocalDateTime lastAt, Integer lastLat, Integer lastLng) {
        UsrAccount a = new UsrAccount();
        a.setId(1L);
        a.setCommunityNo("C-BOUND");       // 用户主动绑的聚落 —— 这列一个字都不许动
        a.setLastLocatedAt(lastAt);
        a.setLastLatE6(lastLat);
        a.setLastLngE6(lastLng);
        return a;
    }

    @Test
    @DisplayName("★★★ 首次（没记过）就写，且 last_* 都写上、community_no 一个字不碰")
    void firstRecordWritesAndNeverTouchesBoundCommunity() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(account(null, null, null));
        svc(mapper).recordLastLocation("U1", LAT, LNG, "R1", "阳光小区", "C-RESOLVED");

        ArgumentCaptor<UsrAccount> cap = ArgumentCaptor.forClass(UsrAccount.class);
        verify(mapper, timeout(1000)).updateById(cap.capture());
        UsrAccount patch = cap.getValue();
        assertThat(patch.getLastLatE6()).isEqualTo(LAT);
        assertThat(patch.getLastPlace()).isEqualTo("阳光小区");
        assertThat(patch.getLastCommunityNo()).isEqualTo("C-RESOLVED");
        assertThat(patch.getLastLocatedAt()).isNotNull();
        assertThat(patch.getCommunityNo())
                .as("patch 不设 community_no → MyBatis-Plus 跳 null，那列纹丝不动")
                .isNull();
    }

    @Test
    @DisplayName("★★★ 节流内 + 没怎么动 → 不写（否则每次定位一次写）")
    void withinThrottleAndNotMovedSkips() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(account(LocalDateTime.now().minusMinutes(1), LAT, LNG));
        svc(mapper).recordLastLocation("U1", LAT, LNG, "R1", "阳光小区", "C-RESOLVED");

        verify(mapper, after(300).never()).updateById(any(UsrAccount.class));
    }

    @Test
    @DisplayName("★★ 节流内但移动超 500m → 写（人真的走了）")
    void movedFarWrites() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(account(LocalDateTime.now().minusMinutes(1),
                LAT - 10_000, LNG));
        svc(mapper).recordLastLocation("U1", LAT, LNG, "R1", "阳光小区", "C-RESOLVED");

        verify(mapper, timeout(1000)).updateById(any(UsrAccount.class));
    }
}
