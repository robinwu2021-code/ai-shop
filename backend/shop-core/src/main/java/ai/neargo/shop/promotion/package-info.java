/**
 * 营销域（新模型，{@code pmt_*}）：券、活动、优惠发生记录。
 *
 * <p><b>与老 {@code marketing} 包的关系是「替换」，不是「并存」</b>：
 * 老包的 {@code mkt_coupon}/{@code mkt_campaign} 一系在 P9 退场。
 *
 * <p><b>⚠️ 2026-09-09 更正：并存期的分流不是开关，是按数据。</b>
 * 这里原先写着「读写走哪一套由 {@code shop.promotion.coupon-model} 一个开关决定 ——
 * 出事时切回去不用改代码、不用回滚发布」。<b>那个开关不存在，而且是被明确否掉的</b>
 * （P4 决策，理由写在 {@code portal.port.CouponPortRouter} 的类注释里）：
 * 开关切错的那一刻，用户手上一整类券会同时失效，而失效的表现与门槛不够、
 * 过期长得一模一样 —— 客服问不出、用户也说不清。
 *
 * <p>实际做法是<b>按这张券在哪张表里分流</b>：存量券在 {@code mkt_user_coupon}，
 * 商家新发的在 {@code pmt_user_coupon}，{@code CouponPortRouter}（以及活动侧的
 * {@code CampaignPortRouter}）逐笔判 {@code owns(...)} 再转发。
 * 两边算价逐分一致由 {@code CouponModelCompatTest} 守着。
 * P9 老表退场时，Router 连同 {@code marketing.port.CouponPortImpl} 一起删掉。
 *
 * <p><b>留着那句话的代价是具体的</b>：真出事时有人会去找一个不存在的开关，
 * 而那正是最不该浪费时间的几分钟。
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
