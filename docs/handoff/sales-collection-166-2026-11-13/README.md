# 166 销售代收与 Plan 发布

2026-11-13。用户确认生产目标为台湾 166（twpay.umeshop.ai），并授权重新创建测试门店验证。

## 发布

- ai-shop 代码 a74da0ae4、接口消费登记 d3e6ada5a；ai-shop-bapp 8851f92。两仓均已正常快进推送 main 与 codex/sales-collection-release-20261113，无强推。共享原工作区和其他会话改动保留。
- 从 origin/main 独立发布分支合并本任务增量，保留现役退款、核款恢复、OTP/进件和 SSE。后端对现役 JAR 比较 61 个业务条目差异，均在商城/销售代收及 V666 范围；不使用此前共享脏工作区制品。
- 166 商城资金实际上由 shop 内 pay-domain、主库 ai_shop_ume 的 stl_payment 管理；虽然部署配置为 standalone，独立 nearpay-prod 没有 PlatformMall/PlatformCollection 类。本轮沿现役归属，不迁移资金所有权或更新独立 PAY。
- V665 已被线上运营进件占用，本轮使用 V666__platform_sales_collection.sql。Flyway 先校验唯一待执行迁移，再实际迁移；历史 SQL/checksum 不改写。销售列/唯一索引已回读。
- .61 上备份 stl_payment、收款档案、Flyway 历史至 /data0/mysql9/sales-collection-backup-20261113/before.sql.gz，SHA256 为 8d9cc299a1d5ebbe418fa824e96fb278cc9b744f0a281a9083bf565cd50d180d；临时 ALTER/INDEX 表级权限已精确撤回。
- 取得既有 flock/mkdir 发布锁后备份 JAR、容器、env 和静态链接。两个 SHOP 消费者停止后原位写入绑定 JAR，保留 inode；仅追加 OFFLINE:SALES_COLLECTION 路由。两服务健康 UP，独立 PAY 的容器和哈希保持。
- 运营静态产物使用 NEXT_PUBLIC_API_BASE=/ops-shop、NEXT_PUBLIC_BASE_PATH=/ops-shop、NEXT_PUBLIC_USE_MOCK=0。原静态资源保留，切换 current 链接；公网 VERSION 正确，页面和 30 个首页资源均 200，未登录商城接口 401。

## 已验证

- 后端合并后 73 项相关用例通过；同凭据并发专项重复 3 次。复验发现并修正销售缺失键 FOR UPDATE 导致的 InnoDB 间隙锁死锁，销售唯一索引负责竞争裁决，银行算法不改。
- OPS 23 项相关单测、类型检查、正式页面生产构建通过。Flutter 复用同任务未变源码的 72 项相关测试和 analyze 证据；锁定 8851f92 的正常 NearBoss 166 debug 包构建并安装到独立 emulator-5560，保留现有 PAYMENT_SSE_ENABLED=true。
- 使用现有运营管理员正常密码登录（沿服务器既有 OPS second-factor=off），未改鉴权设置。自动化旧签名不匹配后没有重配密钥；App 登录滑块由用户人工完成。
- 正式零售 SKU：PRO 30 天 199、365 天 1990（划线 2388）；CHAIN 30 天 599、365 天 5990（划线 7188），币种 TWD。
- 经运营接口建立并回读 UME-TW-BANK-sales v1；平台主体 UME-PLATFORM，期限沿原银行档案 72 小时，销售联系说明不包含虚构联系方式。银行 v2 保留，当前符合范围的销售员工 2 人。
- 用户在模拟器完成验证码后，从正常 App 新建 TEST Sales Plan 20261113：ST261113Y693VWBMKT，主体 M26111119233ZE990，RETAIL/STORE_V1/ACTIVE；使用既有 QA166 Retail Test 主体的第三个门店额度，没有开通门店收款渠道。
- 运营端为新店取得真实 PRO 月付 199 报价、下单 b1d96a07a7494dfaba2f3816f298996d、发出销售代收说明。App 正常采购订单列表及详情回读同一原单，显示待付款、TWD 199、销售代收说明，无银行账户/申报。资金账仍 PENDING，权益仍初始化 FREE，未因下单而开通。
- App Plan 单列表真实显示 30/365 天、年付划线价及省款；选中正式 PRO 年付后得到 TWD 1990、365 天、销售代收的确认单。
- 同店已有进行中方案单时，App 另一下单被业务规则拦截。为继续验证 App 发起购买，运营对自己刚创建且无现金收据的未付测试单走正式作废：RECONCILIATION 私有凭证使用该原单真实待付款截图，原因明确是未收款测试清理，不是收款凭据；SC166-VOID-20261113-01 返回 APPLIED。原单 CLOSED，未伪造实收。关闭改变版本后旧报价正确拒绝，随后重新读取目录和报价。

- App 重新读取正式目录和新报价后，成功创建 PRO 年付 TWD 1990 的销售代收订单 01fc24ae8f2a40779243fe6b0ec40654；运营端回读同一原单/金额/门店。最终资金状态为运营原单 CLOSED、App 年付单 PENDING，两单现金收据均 0，订阅仍 FREE，未生成付费权益来源。

## 并发发布后的收尾核对

后续运营进件发布推进了运行版本：当前 SHOP 哈希 b766cb1d0ad55c75c139be5fc7b1412f471f7700220392242b283e318d7f5bf0，OPS 静态目录 ops-unified-admission-ui-20261113-e02631cd0。没有以本轮旧包覆盖它们。逐项核对本任务改变的 61 个运行条目，全部与本轮正式制品一致；两服务仍 UP，独立 PAY 哈希保持。当前 OPS 引用资源含 SALES_COLLECTION、offline/collectors、collectorNo、collectionReference，记录在 final-ops-ui-audit.json；这是资源/标记核对，不冒充真实核款浏览器提交验收。原本轮发布时的 VERSION 校验保留历史证据，后续静态版本不含该文件。

## 验收边界

未提供实际销售收款事实或私有凭证，不登记虚构实收，不将待付款视作权益开通。真实“运营核款→资金 SUCCESS→权益交付”的线上验收仍需实际收款；本地原生数据库资金/权益专项已覆盖实现。

使用者明确确认后，经正式运营接口停售 4 个历史零售测试 SKU（PRO 11/110、CHAIN 22/220）；正式四个 tw-retail-* SKU 仍 ON_SALE，历史订单快照不改。App 刷新后只显示 FREE 与正式四个周期报价，仍为按等级/总价排序的单列表，无月/年切换。

公开证据见本目录 JSON；服务器私有回滚备份、容器 inspect、口令和会话不进入仓库。App 普通调试包是 166 联调制品，并非应用商店签名发布。
