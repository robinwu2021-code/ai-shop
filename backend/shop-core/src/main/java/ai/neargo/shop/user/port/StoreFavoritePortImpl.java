package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.StoreFavoritePort;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.mapper.UserMappers.StoreFavoriteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StoreFavoritePortImpl implements StoreFavoritePort {

    private final StoreFavoriteMapper favoriteMapper;

    public StoreFavoritePortImpl(StoreFavoriteMapper favoriteMapper) {
        this.favoriteMapper = favoriteMapper;
    }

    /**
     * <p><b>这里不需要 {@code executeWithoutScope}，而且加了是有害的。</b>
     * 第一版写了它，理由是「带域表在 SELF 维度下会 fail-closed 查成空集」——
     * 消融（撤掉它跑本类的测试）两次都没红。查 {@code DataScopeRegistration} 才知道：
     * 登记的 114 张表里<b>没有任何 {@code usr_} 表</b>，数据域是商家/门店维度的，
     * 用户域的表本来就不受它管。{@code StoreFavoriteServiceImpl#countByMerchant}
     * 的注释里已经记过同一件事 —— 我又踩了一遍。
     *
     * <p>多余的 bypass 不是没代价：它制造「这里有个坑已经被防住了」的错觉，
     * 下一个人会照着它在别处也加一层。
     *
     * <p><b>真正要守的是另一件</b>：这是一条<b>跨所有用户</b>的查询，
     * 而 user 域里其余查询都按 {@code SecurityUtils.currentUserNo()} 过滤。
     * 谁顺手给它加一个 userNo 条件，上新通知就会只发给「当前登录的那个人」——
     * 而在 Outbox 投递线程里没有登录的人，于是一条都不发，零报错。
     * {@code NewGoodsNotifyFlowTest#followersVisibleRegardlessOfCaller} 钉着这一条。
     */
    @Override
    public List<String> followerUserNos(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return List.of();
        }
        return favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .select(UsrStoreFavorite::getUserNo)
                        .eq(UsrStoreFavorite::getEntityNo, merchantNo))
                .stream().map(UsrStoreFavorite::getUserNo).distinct().toList();
    }
}
