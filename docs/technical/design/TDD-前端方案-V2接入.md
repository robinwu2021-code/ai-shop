# TDD 前端方案 · V2 接入（b-app / c-app / ops-web）

状态：**草稿 · 待确认** · 创建 2026-08-28 · 第二梯队 #7
定位：后端 V2 的前端侧成册。核心风险自始至终是一条：**后端有字段而前端没入口 = "配了没生效"且不报错。**
上游：[09 API 参考](../../docs/v2/09-API参考.md)（若从仓库根读：docs/v2/09-API参考.md）· ADR-020（读可聚合）· 术语层（对齐清单 §四）

---

# 一 · 决策记录

| # | 决策 | 一行理由 |
|---|---|---|
| 1 | b-app 建品页**改为 form-schema 驱动渲染**，弃 `type` 硬渲染；**与后端段落地同 PR** | 三行业一套建品页；不同 PR 就会出现有段无入口 |
| 2 | 工作台**按能力渲染，不按行业**：入口显隐由 `GET /biz/store/capabilities` 决定 | 前端零 `if(行业)`（与后端同一条纪律） |
| 3 | 行业页面在 `pages.json` **始终存在，入口按能力显隐** | uni 分包不支持按商家动态装（既有结论） |
| 4 | 术语层是**数据**：form-schema 下发词条包（`labels` 字段），组件原样渲染 | 「出品部门/菜单分类」是文案不是分支 |
| 5 | C 端商品列表/详情**只接合成视图**（storefront），端上不拼价格/时段/沽清 | 拼错了没人发现（read 聚合的全部意义） |
| 6 | 收银台、KDS、桌台图、排程表是**四套独立界面**，各挂能力码 | 后端不分叉不等于前端不分叉（早有结论） |
| 7 | Mock 与真实一键切换沿用现有请求层；**新接口先补 mock 再开发** | ops-web mock 自查的既有教训 |
| 8 | 改完界面**必重跑 `gen-ui-catalog.py`**；改 `.vue` 跑 `vue-tsc` 不是 `tsc` | pre-push 两道既有闸 |

# 二 · b-app（商家端）改造清单

| # | 界面 | 改法 | 依赖接口 |
|---|---|---|---|
| B1 | **建品页**（三行业一套） | 首屏**先选门店**（评审 B3 推荐；单店自动跳过）→ 拉 form-schema → 按 `common/traits/modifierGroups/specs` 四段渲染；段内字段 `optionsFrom` 动态取选项 | `/biz/goods/form-schema` `/biz/goods/save` |
| B2 | 选配组管理 | 新页：组 CRUD + 选项（定额/比例/负加价三态输入）+ RESOURCE 源只读提示 | `/biz/modifier-groups` |
| B3 | 门店售卖设置 | listing 编辑（渠道/工位/道次/限量/MOQ）+ 沽清一键 | `/biz/listings/*` |
| B4 | 陈列分组（菜单分类/项目分组/商品分组，**词条按行业**） | 拖拽排序 + smart 规则表单 | `/biz/stores/{s}/collections` |
| B5 | 价格页 | 渠道×客群矩阵 + 生效窗（时价）；resolve 试算器 | `/biz/price-entries:batch` `/biz/price:resolve` |
| B6 | **收银台**（零售能力） | 开单→扫码/杂项行→多笔收款→结清；FEE 行名称必填 | `/biz/cashier/*` `/biz/payments*` |
| B7 | 收款与交班 | 按 store+时段列 `ord_payment`；FAILED 打印任务入口 | `/biz/payments` `/biz/print-jobs` |
| B8 | 打印设置 | 设备（测试页）/模板（preview）/路由（**dry-run 演练**） | `/biz/printers*` 等 |
| B9 | 资源与排班（能力显隐） | 资源 CRUD、人员与技能、排班规则、**调班影响面确认框**（affected_holds） | `/biz/resources*` `/biz/staffs*` `/biz/schedule*` |
| B10 | 会员资产查询 | 客资产页（余额/卡/流水），耗卡入口在行业界面 | `/biz/members/{m}/assets` |
| — | 行业界面（桌台图/KDS/预约排程/工单） | 归行业包前端模块，能力显隐；本册只管入口 | `/x/food/**` `/x/beauty/**` |

# 三 · c-app（买家端）

| # | 界面 | 改法 |
|---|---|---|
| C1 | 店铺页 | 全量接 storefront：分组 tab（词条随行业）+ 售罄/时段置灰 + 已解析价 |
| C2 | 商品详情 | modifiers 选择器（必选组不选禁下单，**约束在端上先验、服务端复验**）；caution 弹层（交易前置，不是详情段落）；售后策略展示 |
| C3 | 下单页 | lines[].modifiers 提交；行金额=响应回显（端上不算钱） |
| C4 | 预约流程（能力显隐） | 项目→技师→时段（`/mp/booking/slots`）→hold TTL 倒计时→下单/到店付分支 |
| C5 | 我的资产 | 余额/卡/余次 + 近流水 |

# 四 · ops-web

行业配置页（product-types，改 traits 走审批交互）· 能力开关页（留痕）· 打印模板平台默认维护。
**菜单在库里** —— 新页面经迁移 INSERT，别只改 nav.ts（既有教训）。

# 五 · 施工顺序与闸门

与后端阶段对齐：P1 前完成 B1（同 PR 硬约束）+ C1–C3；B6/B7 随收银台；B9 随预约域端点。
闸门：`gen-ui-catalog.py --check` · `vue-tsc` 两端 · 新页 mock 齐备 · **B1 上线判据：三行业各建一件商品全程不碰开发者工具**。
