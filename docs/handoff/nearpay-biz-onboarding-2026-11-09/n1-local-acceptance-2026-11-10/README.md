# N1 本地联调证据（2026-11-10）

结果：真实商户USR密码登录、App选店与独立PAY双JVM认领通过。两笔host业务单COMPLETED、PAY原单COMMITTED；一次真实PAY commit回包丢弃后，host保留LOCAL_COMMITTED，文件journal冷恢复和两JVM同库重启后以原键显式继续。纯查询无状态推进、恢复请求不携旧店头。最终两店投影归属T且修订前进；旧来源同步HTTP409，未覆盖已确认投影。

- `result.json`：runner跨库/投影及故障计数结果。
- `dart-result.json`：实际Dart请求与原单阶段；无令牌/口令/私有材料。
- `source-hashes.json`：证据采集时N1关键源码摘要；不将其他会话改动冒充本批作者。

运行：`python3 scripts/verify-store-claim-local.py`，仅127.0.0.1:3308及临时隔离schema。runner已终止两进程、删除ready凭据文件并清理schema；没有访问/部署166、没有渠道调用、没有git提交/推送。

边界：host轻量Tomcat测试装配使用真实认证、DB会话、mapper、认领/商户读服务、CORE历史、commerce与生产PAY HTTP客户端；不相关portal依赖仍为测试stub，critical audit stub实际写同行事务DB。最终投影用受保护loopback hook调用生产NearPayStoreSyncConsumer，未覆盖outbox worker调度。新建默认关闭；非RETAIL未有明确能力映射、独立库存启用部署及已有商品/经营配置等按实际blocker拒绝，未实现库存/配置资产迁移。

定向检查：host协调12、配置写门闩3、履约20、范围保存1通过；CORE历史34/商品写门闩3、普通库存13通过；独立PAY预留/退休24、执行环境12、Spring代理1通过。App原认领11及新增库存文案1已过；经营配置文案1亦已定向验证通过。旧商品流程46中36通过、10因HEAD已有MySQL FORCE INDEX在H2不兼容失败，未修改该生产SQL，未声称旧HEAD基线已跑。
