# 开店经营实体名称增量

日期：2026-11-14。状态：本地实现与 MySQL9 定向验证完成，未部署。

## §0 需求到设计

| 要求 | 实现 |
|---|---|
| 店名和经营实体名称可以不同 | QuickStartReq / QuickStartCommand 新增可选 entityName |
| 兼容旧包 | 省略 entityName 沿用 storeName；旧构造方法保留 |
| 不覆盖待补件实体名称 | 重放带不同 entityName 返回 CONFLICT |
| 老服务兼容 | store-creation-context 返回 entityNameSupported，旧服务不返回时 App 暂不允许独立输入 |
| 不推断支付或行业权限 | 原额度、免费初始化、行业目录及建店事务保持原链路 |

## §1 模型与链路

前台经营实体仍映射商家主体 entityNo；不改 independent operatingEntityId 的语义。BizMerchantController → MerchantAdminPort → MerchantPortImpl.quickStart，在原事务中分别写实体名和默认店名。名称 trim 后 1–100 Unicode 字符，无迁移。已有主体新增门店沿用 branch，不经过名称覆盖。

## §2 兼容与风险

已有待补件壳仍以原账号范围和原市场检查为准，名称不同不会新建第二个壳。不同名称冲突由 App 展示冲突提示，不能偷偷修改。新增字段不授予认证、支付资格或行业套餐。后续增量已实现 requestNo 事务回执和版本化分区更新，见 [开店编排与分区版本](TDD-门店开店编排与分区版本.md)。

## §5 验证与未覆盖

`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -pl shop-app -am -Dtest=BizStoreSetupTest -Dsurefire.failIfNoSpecifiedTests=false test`：20 项通过，包含独立实体名传递与旧构造方法兼容。

Flutter 合同及 mock 检查覆盖：独立名称、首店与二店四步流程、复用实体不自动启用渠道、免费门店及分店不获得行业权限。API 文档与 App 镜像同步。

本轮新增 MySQL9 原生事务、并发、额度、结果恢复和真实会话 HTTP 验证；执行范围及证据见后续 TDD。Controller 单元测试不替代数据库验证。未运行 H2，只新增 V673，未修改已发布迁移或部署到 166。
