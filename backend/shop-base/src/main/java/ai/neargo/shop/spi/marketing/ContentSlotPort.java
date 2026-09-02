package ai.neargo.shop.spi.marketing;

import java.util.List;

/**
 * product → marketing：首页推荐位**这一刻**该展示哪些货。
 *
 * <p><b>它补的是一段「凑合」而不是一块空地</b>：{@code GoodsServiceImpl#promoted}
 * 一直按销量倒序取货，注释里写着「一期无运营后台，按销量兜底……接上运营配置时只换这一段」。
 * 也就是说首页那一屏展示的是**销量事实**，而页面上写的是「推荐」——
 * 运营想推一件新货，唯一的办法是让它先卖起来。
 *
 * <p><b>没配就返回空，由调用方兜底</b>，而不是在这里回退到销量：
 * 兜底规则属于商品域（它知道社区池、上架状态、审核状态怎么算），
 * 放到营销域来实现会变成第二处「首页该展示什么」的判断，两处迟早分岔。
 */
public interface ContentSlotPort {

    /**
     * 当前生效的首页楼层商品（**有序**，多个楼层按 sort 依次拼接、跨楼层去重）。
     *
     * <p>「生效」= 未归档 + 开关开着 + 此刻在上下线时间之间 +
     * 投放社区命中（内容位没写社区 = 全部社区）。
     *
     * @param communityNo 当前社区；为空表示用户还没选社区，只取「全部社区」的位子
     * @return 货号；<b>没有任何生效的位子时返回空表</b> —— 调用方据此走自己的兜底
     */
    List<String> homeFloorGoodsNos(String communityNo);
}
