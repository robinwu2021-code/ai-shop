package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.mapper.UserMappers.StoreFavoriteMapper;
import ai.neargo.shop.user.service.StoreFavoriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「N 人收藏本店」这个数（TDD-C 端裂变与商家招募 §8.2 批 2）。
 *
 * <p><b>它与其余收藏方法是反方向的查询</b>：那些按当前登录人查，这一个按店查、跨所有人。
 *
 * <p><b>这个类只守「跨用户计数对不对」</b>（数得全、别家的不混进来、空店号不去数全表）。
 *
 * <p>⚠️ <b>一开始这里声明过守「绕数据域」，那是错的</b>：撤掉 impl 里的
 * {@code executeWithoutScope} 这三条照样全绿，查了 {@code DataScopeRegistration}
 * 才知道登记的 114 张表里没有 usr_ 族 —— 这张表压根不受数据域管，
 * 那个 bypass 是多余的（已删）。
 *
 * <p>真实链路那一半由 {@code M9aOpsFlowTest.bizProfileShowsFavoriteCount} 守：
 * 带 B 端令牌走 {@code /biz/merchant/profile}，验商家端真的拿到了跨用户的数。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreFavoriteCountFlowTest {

    @Autowired
    private StoreFavoriteService favoriteService;
    @Autowired
    private StoreFavoriteMapper favoriteMapper;

    private final List<Long> created = new ArrayList<>();

    /** 造的收藏行要删掉 —— 它进的是共享种子库，留着会让别的测试数出别的数 */
    @AfterEach
    void cleanUp() {
        created.forEach(id -> DataScopeContext.executeWithoutScope(() -> favoriteMapper.deleteById(id)));
        created.clear();
    }

    private void seedFavorite(String userNo, String merchantNo) {
        UsrStoreFavorite row = new UsrStoreFavorite();
        row.setUserNo(userNo);
        row.setEntityNo(merchantNo);
        DataScopeContext.executeWithoutScope(() -> favoriteMapper.insert(row));
        created.add(row.getId());
    }

    @Test
    @DisplayName("★★★ 跨用户数：三个人收藏同一家 → 3（按登录人查的话恒 1 或 0）")
    void countsAcrossAllUsers() {
        String merchant = "M-FAVCNT-" + System.nanoTime() % 100_000_000L;
        assertThat(favoriteService.countByMerchant(merchant))
                .as("造之前必须是 0 —— 否则下面那条证明不了是这三行数出来的")
                .isZero();

        seedFavorite("U-FAV-A" + System.nanoTime() % 1_000_000L, merchant);
        seedFavorite("U-FAV-B" + System.nanoTime() % 1_000_000L, merchant);
        seedFavorite("U-FAV-C" + System.nanoTime() % 1_000_000L, merchant);

        assertThat(favoriteService.countByMerchant(merchant))
                .as("没绕数据域的话这里是 0（fail-closed 拼成 1=0），而商家页会一直说「还没人收藏」")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★★★ 别家的收藏不算进来 —— 少一个 where 就变成「全平台收藏数」")
    void doesNotCountOtherMerchants() {
        long n = System.nanoTime() % 100_000_000L;
        String mine = "M-FAVCNT-MINE-" + n;
        String other = "M-FAVCNT-OTHER-" + n;
        seedFavorite("U-FAV-X" + n, mine);
        seedFavorite("U-FAV-Y" + n, other);
        seedFavorite("U-FAV-Z" + n, other);

        assertThat(favoriteService.countByMerchant(mine)).isEqualTo(1);
        assertThat(favoriteService.countByMerchant(other)).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 店号为空/null → 0，不抛也不去数全表")
    void blankMerchantIsZero() {
        assertThat(favoriteService.countByMerchant(null)).isZero();
        assertThat(favoriteService.countByMerchant("")).isZero();
        assertThat(favoriteService.countByMerchant("   ")).isZero();
    }
}
