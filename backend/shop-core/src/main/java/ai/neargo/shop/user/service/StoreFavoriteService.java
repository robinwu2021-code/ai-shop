package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.StoreBriefVO;

import java.util.List;

/**
 * 收藏的店（C-ST-07/08）。
 *
 * <p>店铺码的解析与生成已迁往 merchant 域的 {@code StoreCodeService}——
 * 那两个方法会读写 {@code mch_entity}，本不该由用户域承担。
 */
public interface StoreFavoriteService {

    /** 「我的常去店」= 收藏 + 归因命中的店，去重后按最近优先。 */
    List<StoreBriefVO> myStores();

    /** 收藏/取消，返回最新列表。 */
    List<StoreBriefVO> toggle(String merchantNo);

    boolean isFavorited(String merchantNo);

    /** 我的收藏 · 店铺：<b>只有收藏</b>，不混入归因店（那是 {@link #myStores()} 的「常去店」）。最近收藏在前 */
    List<StoreBriefVO> favorites();

    /**
     * 这家店被多少人收藏（TDD-C 端裂变与商家招募 §8.2 批 2）。
     *
     * <p><b>与「我的收藏」是反方向的查询</b>：上面几个方法都按当前登录人查，
     * 这一个按店查、跨所有人。谁顺手给它加一个 userNo 条件，
     * 商家页就会永远显示 0 或 1 —— 而那看起来完全像「真的没人收藏」。
     *
     * <p>（第一版以为它要 {@code executeWithoutScope}，实测不需要：
     * 数据域登记的 114 张表里没有 usr_ 族，用户域的表不受商家维度管。见 impl 注释。）
     *
     * <p>为什么要它：收藏功能上线以来线上 0 行 —— 入口在、接口通，
     * 没人点是因为收藏了什么都不发生。让商家看到这个数、让买家看到社会证明，
     * 是给这个动作一个理由的第一步。
     */
    int countByMerchant(String merchantNo);
}
