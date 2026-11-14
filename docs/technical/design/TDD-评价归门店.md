# TDD · 评价归门店

> 状态：**已实施**（2026-08-14）· 依据：[ADR-011](../ADR/ADR-011-经营主体与门店边界.md) 决定表第 3 行
> 上游：[增值包-全链路执行计划](./增值包-全链路执行计划.md) P2 留下的缺口

## 一、要解决的问题

ADR-011 早就定了：**评价归门店**。原话是

> 顾客评的是「楼下那家」，三家店评分混成一个，好店会被差店拖下去。

但它一直没实现 —— `rvw_review` 上只有 `entity_no`，没有 `store_no`。
后果不是「少一个字段」，而是**已经交付的功能对不上需求**：

| 谁在等这个 | 现在的样子 |
|---|---|
| 跨店对比（B-11.12.6，增值包卖的东西之一） | 评分只能放在页面顶部作**主体级**说明，还要专门写一句「为什么三家店是同一个数」 |
| C 端门店主页 | 一家新开的分店直接继承老店的分；一家被投诉的店把整个主体拖下水 |
| 运营端门店档案 | 「这家店经营得怎么样」答不出评分这一项 |

**这不是新功能，是把一条已经写进 ADR、且下游已经按它设计的决定补上。**

## 二、模型

### 2.1 评价落哪个门店

**落下单那一刻子单上的 `store_no`**，不是「商家现在的默认店」。

理由是同一条：快照 vs 现状。顾客评的是**当时那家店**给他的体验 ——
半年后商家把那家店关了、或者把默认店换成新店，这条评价不该跟着搬家。
`ord_sub_order.store_no` 正是下单时固化的那一列（ADR-011 P2），直接取它。

**老评价 `store_no` 为空**，且**不回填成默认店**：
没有门店信息的历史评价，硬塞给默认店会让那家店的分凭空多出一批来路不明的评价。
空就是空 —— 它照常计入**主体**评分，只是不计入任何一家门店。

> ⚠️ 例外：能从 `ord_sub_order` 反查到 `store_no` 的**要回填**。
> 那不是猜，是把当时就存在的事实补进来。

### 2.2 门店评分存哪

与主体评分同构：**派生列存在 `mch_store` 上**（`rating` / `rating_count` /
三个维度分），由 `MerchantRatingPort` 覆盖写。

不现算的理由与 `mch_entity` 那份完全一样，写在 Port 的注释里：
读评分的地方（C 端门店主页、跨店对比、运营端门店档案）都是列表页，
现算等于每页做一次 group by；而评分变化只发生在「发表 / 审核 / 撤下」三个时刻。

**口径与主体评分逐字相同**（含 180 天半衰期的时间加权）——
两套口径的后果是「主体 4.6 分，三家店分别是 4.8/4.7/4.9」，
而没有人能解释那个 4.6 是怎么来的。

### 2.3 重算的触发点

`recomputeRating` 现在算两处（商品、主体），改成三处（商品、主体、**门店**）。
门店那一处**只在评价带 `store_no` 时算** —— 老评价没有门店，不该去更新一个它不属于的店。

## 三、影响面（先数调用方，§6.2 的规矩）

| 位置 | 动作 |
|---|---|
| `ReviewableOrderPort.ReviewableItem` | 加 `storeNo`（来源是子单，Port 已经在读那张表） |
| `ReviewServiceImpl#create` | 写入 `store_no` |
| `ReviewServiceImpl#recomputeRating` | 多算一处门店 |
| `MerchantRatingPort` | 加 `updateStoreRating(storeNo, Rating)` |
| `BizCrossStoreController#compare` | 评分从**顶层**挪进**每一行**；顶层那句「主体级说明」删掉 |
| `CrossStoreCompareRow`（shared 类型） | 加 `rating` / `ratingCount` |
| b-app 跨店对比页 | 表格加一列；删掉「为什么三家店是同一个数」那段说明 |

**不动的**：C 端商品评价列表、商家回复、审核链路 —— 它们读的是评价明细，与归属无关。

## 四、验收

1. 两家店各下一单、各评一次（4 星 / 2 星）→ **两家店的分不同**，且主体分是两者的合成
2. 老评价（`store_no` 为空）**照常计入主体分**，不计入任何门店
3. 评价被驳回 → 该门店的分跟着变（与主体同一条规矩：只算审核通过的）
4. 跨店对比里**每行有自己的分**，撤掉门店重算就会红

## 五、C06 评价回复与申诉正式接线（2026-11-10）

### §0 范围与需求对账

延续用户C06零售App补齐，库存最后；对应全量清单REVIEW01。沿现有评价一次回复、低于3星一次申诉及平台裁决，不改评分归店、原订单、权限或库存。当前商家列表按主体返回可见评价，与门店评分归属是两件事；App明确主体范围，不能把所加载页面指标当全量门店评分。

| AC | 落点 | 验证 |
|---|---|---|
| RV01 正式scores/回复时间/申诉原号，列表超过50可继续 | ReviewsApi/ReviewsPage、BizReviewController既有list分页入参 | App正式JSON/分页；后端控制器合同 |
| RV02 已下架评价的本主体申诉裁决仍可回读，越主体不可读 | 新增GET /biz/review/appeals与GET /biz/review/{reviewNo}、ReviewService | 后端隔离/历史；App历史与原号 |
| RV03 回复/申诉一次，未知结果先回查，不重复POST；权威回执保留 | 原号详情回读、页内未决状态、列表/历史独立恢复 | App写成功后读失败/网络未知/错号 |
| RV04 切店、主体、用户、会话或review权限失效隐藏旧页/弹层，禁旧提交/上传 | 原范围API与页面守卫、既有上传isCurrent可选参数 | App范围失效/权限/上传 |

### §1 当前合同与选择

正式ReviewVO为scores而非subScores，包含merchantNo/repliedAt，Appeal含appealNo/reason/status/verdict，没有at，不推断裁决时间。当前GET /biz/review固定第一页50条且只看PASSED，无法读UPHELD隐藏评价。本批给既有列表开放page/size（默认仍1/50，上限50），另补本主体申诉分页与原评价详情；均沿biz:review和BizContext.requireMerchantNo，不开放运营接口。详情包含原评价和申诉，仍复用ReviewVO，无DDL或新权限。

选择增量扩展现有控制器/Service读取合同；只修App会保留50条截断和隐藏裁决盲区，另造评价领域则重复已有规则。App列表和申诉历史独立续页/重试，概览只描述“已载入的主体可见评价”，不声称权威全量评分/回复率。历史显示裁决说明并可查原评价，不把UPHELD历史混入可见评价平均分。

### §2 内联实施计划

- [x] 后端ReviewService/impl及BizReviewController新增归属受限详情和申诉分页，原列表接受分页；保持原写规则与公开可见查询。
- [x] App正式模型、原范围API、列表/历史续页与原评价回读；修正scores和时间，未知写入冻结并查询原号。
- [x] 回复弹层与申诉/上传冻结原身份和review权限；mock同步正式字段及隐藏评价历史，保持真实入口。
- [x] 完成编码后集中执行受影响App/后端合同与规则验证、限定静态及四语言词条；一次只读复核，必要时仅增量修复验证。
- [x] 回填本节、主复核报告/两份CSV及App交接；不提交部署，库存仍后置。

### §5 验证与边界

App新增17项分组/增量通过：`reviews_contract_test.dart`正式字段/分页/身份及权限/回执6项，列表续页重试、独立隐藏裁决历史、成功回执保留、未知回复/申诉及原号查询、切店弹层、迟到相机、撤权和返回重进恢复11项。既有`v27_flows_test.dart`的V01–V03真实导航→筛选→申诉→回复1项通过；mock不再把UPHELD隐藏评价计入可见平均分。限定7个文件analyze通过，新增7词条四语言及占位符对账通过。

`MerchantReviewReadNativeMysqlTest`在127.0.0.1:3308的MySQL9创建随机隔离库，使用真实ReviewMapper/AppealMapper/ReviewLikeMapper与本批ReviewServiceImpl，5项、0跳过分组/增量通过：
- `hiddenReviewCanBeReadOnlyByOriginalMerchant`：隐藏原评价归属、原申诉号/裁决、scores/repliedAt；外主体拒绝。
- `appealsArePagedScopedAndKeepHiddenVerdicts`：52条分50/2，无重叠，外主体不混入，隐藏裁决保留，空历史。
- `bizRoutesUseContextAndExposeRealScoresAndPagination`：独立MVC映射使用BizContext，既有可见列表第二页、详情与申诉路由；这不是实际认证过滤器联验。
- `replyStillBelongsToOriginalMerchantAndCannotBeRepeated`：原主体回复、去空白、第二次拒绝、回读首个结果。
- `lowStarAppealRemainsOnceOnlyAndRejectsHighStars`：原主体一次低星申诉、外主体/重复/高星拒绝。

原生测试首轮4/5通过；回复更新夹具缺少生产已有OptimisticLockerInnerInterceptor，补回该插件后只重验失败1项通过，没有修改业务写规则或冻结DDL。每次测试结束删除本次临时库，未接触既有业务数据。当前三个Java生产类与原生测试限定编译通过；运行依赖沿既有隔离快照，不能宣称当前共享工作区整仓重建/完整Flyway或应用部署通过。

早期隔离快照首轮新增读取3项通过；另选6项既有整单评价旅程均在评价前的confirmReceipt触发`Checkout authorization requires no caller transaction`，不据此宣称完整订单旅程通过，也未修改订单生产代码。随后改用上述真实MySQL评价边界验证本批读取与原有回复/申诉规则，该失败边界保留在记录中。

一次只读review两处P2均修复：未决申诉仅保存在子路由导致返回重进可POST，以及回复弹层固定标题暴露旧顾客姓名。未决回复/申诉保存在当前控制器会话内，按session/user/merchant/review原号隔离，重进只回查；顾客身份放入受范围守卫的正文，失权即隐藏。相应恢复与姓名断言包含在17项中，不累计重复轮次。列表滚动fixture和缺失AppResult测试导入亦已修正。

运行证据：`/tmp/ai-shop-c06-reviews-app.log`、`-app-incremental.log`、`-pagination.log`、`-recovery.log`、`-journey.log`、`-analyze-final.log`、`-native.log`、`-native-reply.log`；早期限制见`-backend.log`（均使用同一`ai-shop-c06-reviews`前缀）。本地接线与合同通过不代表真实图片供应商、运营裁决验收或生产发布。

### §6 设计 → 实现

| §2设计 | 实现与证据 |
|---|---|
| 原服务扩展读取 | ReviewService新增merchantReview/merchantAppeals，impl复用原VO与原归属查询，申诉分页显式按entityNo且回读原评价时再限同主体；BizReviewController保留biz:review，既有列表开放page/size，新增两条GET |
| App正式合同与恢复 | 从原页面提取reviews_api.dart，校验正式字段/原号/merchantNo与当前权限；reviews_page.dart区分可见列表和申诉历史、独立续页重试、已载入范围概览及原号结果查询；OnboardingController只增加本会话两类未决原号集合 |
| 上传与mock | OnboardingApi.upload/_image增加默认不改变原行为的isCurrent可选参数，沿已有图片格式验证/错误框架；mock_store_routes按主体隔离、正式scores/appealNo及列表/详情/历史形状服务 |
| 测试/i18n | 新增原生MySQL和17项App合同测试，既有v27评价fixture适配隐藏历史；四语言7个词条及生成文件同步 |

没有新增DDL/迁移/权限或评分算法，没有修改原回复/申诉/裁决的写规则，live写白名单、地区、支付及库存开关未变；未提交部署。原清单未单列评价，本批将其纳入RET-09组合功能组并保留原66组/25任务ID，不把组合组数当原子功能完成率。库存I01/I04与指定商家全链仍最后执行。
