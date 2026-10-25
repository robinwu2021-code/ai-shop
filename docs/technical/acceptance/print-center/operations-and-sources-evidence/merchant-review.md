# 商家打印入口最终小差异审查

范围：`/private/tmp/ai-shop-print-compact-integration` 的 `b-app/src/pages/print-center/index.vue`、组件测试，以及为核对来源身份所需的既有 backend resolver。只读，不修改源码、不运行编译或测试。

## 最终结论

Spec：静态审查通过。

Quality：静态审查通过。发现的3项Important均已修复并只读复核，当前无未决Critical / Important。未运行测试或构建，实际通过结果以root集中验收为准。

### [Important，已修复] 原订单入口选择退款后仍把原订单号作为退款 sourceNo

- 位置：`b-app/src/pages/print-center/index.vue:57`。
- 触发：以原订单号/子订单号深链进入；summary 聚合返回 REFUND 事实；选择 REFUND_RECEIPT 后提交。
- 当前行为：J03 sourceRef.no 使用页面 `orderNo`。但 `BusinessPrintFactServiceImpl.resolveReference` 只接受该 fact 的 `source_no`，而退款 source_no 是 afterSaleNo；因此必然 `print.factNotFound`。直接以 afterSaleNo/rechargeNo 传入 orderNo 深链时可工作，未覆盖原订单聚合入口。
- 建议：在严格 scope + type + factNo + revision + scene 限制下，让 business resolver 支持 summary 查询的同一 canonical/order/subOrder 别名；或明确提供 canonical sourceNo 并客户端提交该值。保留不匹配号码拒绝。
- 测试问题：当前混合 ORDER_PAID 与 REFUND fixture 共用 `BUSINESS-1`，未表达真实 orderNo 与 afterSaleNo 不同，不能证明该入口可用。

### [Important，已修复] 同场景多条退款事实的 picker 会提交第一条

- 位置：`b-app/src/pages/print-center/index.vue:22,79,100`。
- 触发：一个订单有两次部分退款，两条 fact 均为 REFUND_RECEIPT；在 source picker 选择第二条。
- 当前行为：picker 按 fact 列表展示，但 onchange 只记录 scene，selectedFact 再 `find(scene)`，始终取第一条。因此选择第二条仍提交/模拟第一条，显示名也无法辨识二者。
- 建议：保持显式 fact 身份（factNo/版本或选中index），scene 只作为其场景；picker 展示可区分来源身份，切店清空，刷新仅保留仍存在的选中事实。补相同 scene 两条 fact 的组件回归。

## 前两项修复复核

- REFUND resolver 现在在 tenant/entity/store/type/factNo/revision/scene 全部 AND 条件内，使用括号包围 `(source_no = no OR order_no = no OR sub_order_no = no)`；RECHARGE仍只接受source_no。完整snapshot ref/hash检查未改写。真实退款flow测试读取真实order/subOrder，对应别名可解析同一个fact，错误店/无关单号/错误revision拒绝；unit验证query字段及充值无order别名。
- 客户端显式保存 selectedFactNo + selectedFactRevision，selectedFact同时匹配scene+身份。picker逐条选择该fact，标签显示factNo+revision；刷新保持仍存在的选中身份，切店清空。组件测试新增两条同REFUND_RECEIPT事实，选择第二条并刷新后确认提交仍为第二fact及revision。

## 最后一项修复复核

### [Important，已修复] A→B→A切店后原A请求仍可能回写旧操作结果

- 原位置：`b-app/src/pages/print-center/index.vue` 的 `run()`，此前只捕获/比较storeNo字符串。
- 触发：A店操作pending，切到B再切回A，原A操作此时成功/失败返回。
- 影响：因为storeNo再次相等，旧操作可重写被清除的latestOperation/error；成功时外层还会切换模拟/导出tab，不满足切店丢弃旧结果。
- 最终修复：独立scopeGeneration在切店和unload时递增；run捕获generation与store，current()同时比较两者，成功写latestOperation前、失败写error前和返回success前均检查。load自己的epoch不会误使当前操作失效。已静态核对组件成功/失败两种ABA回归：切S2再回S1后resolve/reject旧请求，不显示旧成功或错误、不切tab、不发后续刷新。

## 已核对通过的部分

- J11 入口允许订单权、真实售后权或充值读取且账户读取权；权限字符串与 backend BizPerms 一致，仍需 biz:print:read。
- 授权来源 picker 位于 summary，独立于 simulate 区域，因此无 simulate 权的充值专岗也可以选择来源。
- J03 body 的 scene、type、factNo、revision 来自选中事实；无对应 scene fact 时隐藏提交按钮。上述 no 和多fact选择问题已通过最终修复构成完整绑定。
- ORDER_PAID 原流程继续支持原 orderNo/subOrderNo；其既有 backend resolver 已接受二者别名。
- source 深链仍沿 `orderNo` query 参数，J11 原路径编码可传 afterSaleNo/rechargeNo，无需改API URL。
- 后台15秒 poll 使用 `load(false)`，成功刷新不会先清空操作错误；模拟/导出操作失败不再跳至成功列表。
- 切店清空 summary、选中fact、详情、latestOperation 与幂等意图；列表及详情结果有 epoch/store 守卫，run 成功/失败有独立scopeGeneration+store守卫，覆盖普通切店和A→B→A迟到结果。

## 验证边界

本报告只给静态结论，未运行组件测试、类型检查、构建或浏览器。运行证据由 root 集中验收记录；模拟成功、PDF就绪不代表真实打印完成。
