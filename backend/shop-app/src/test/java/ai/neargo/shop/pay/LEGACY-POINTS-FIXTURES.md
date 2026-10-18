# 旧积分测试夹具

本目录中的 PointsService、PointsServiceImpl、PointsPortImpl、PointsConfig、PointsVOs
及 test/java/ai/neargo/shop/spi/settle/PointsPort 仅供尚在迁移的历史保护用例使用。
它们已退出生产模块的 src/main，不进入任何生产 jar；禁止生产代码引用。

当前积分业务使用 pay.points 内核、spi.points 契约及订单源事件日志。
新功能验收必须使用新模块的测试证据；历史夹具测试不计入第三阶段业务验收数量。
历史用例逐项迁移后应删除对应夹具，不应再增加旧引擎功能。
