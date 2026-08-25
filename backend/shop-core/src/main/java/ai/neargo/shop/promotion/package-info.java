/**
 * 营销域（新模型，{@code pmt_*}）：券、活动、优惠发生记录。
 *
 * <p><b>与老 {@code marketing} 包的关系是「替换」，不是「并存」</b>：
 * 老包的 {@code mkt_coupon}/{@code mkt_campaign} 一系在 P9 退场。
 * 在那之前两套表同时存在，读写走哪一套由 {@code shop.promotion.coupon-model}
 * 一个开关决定 —— 出事时切回去不用改代码、不用回滚发布。
 *
 * <p><b>依赖方向</b>：promotion → member（问「他是不是熟客、在不在这个人群里」），
 * member 不问 promotion。跨域一律走 {@code ai.neargo.shop.spi.*} 的 Port。
 *
 * <p><b>这个域碰钱</b>。三条不能破的规矩：
 * <ol>
 *   <li>算优惠只有一处实现（{@code PmtCoupon#discountFor}）——
 *       老模型分岔过一次：下单算价认得折扣券，而「最优券」只看面额，
 *       于是折扣券永远推荐不出来，两边代码各自都说得通。</li>
 *   <li>发行量与预算是<b>并发计数器</b>，不是缓存：防超发靠一条带条件的 UPDATE，
 *       用 COUNT 代替就没法在同一条语句里判「还有没有」。</li>
 *   <li>券的每一次使用记一行 {@code pmt_apply}，线上线下同一张表 ——
 *       两张表相加对账，迟早会有一天加不上。</li>
 * </ol>
 */
package ai.neargo.shop.promotion;
