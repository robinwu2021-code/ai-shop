# 提交快照验证记录

日期：2026-11-11。对象为从 Git index 导出的仅库存提交快照，不读取共享工作区未提交的业务实现；独立库存先从此快照 install，再编译同份宿主/FOOD。前端 node_modules 复用本机依赖，源代码和资源来自拆分后的快照。JUnit classpath 已排除旧临时测试 classes 和工作区 target，项目 classes 全部指向提交快照。

| 检查 | 真实结果 | 本机日志 |
|---|---|---|
| 独立 inventory reactor | BUILD SUCCESS，6 模块；本次执行 main install，不宣称全量测试通过 | `/tmp/i04-handoff-inventory-build.log` |
| 宿主/FOOD reactor | BUILD SUCCESS，45 模块，`food-embedded,food-verify`，shop-app 与 food-server 及必要依赖 | `/tmp/i04-handoff-host-build.log` |
| 限定后端 JUnit | 首轮 99 方法：96 成功、0 失败、3 因未设置 I04A 原生测试开关而中止；补齐环境后只重跑这 3 项，3/3 通过。合计 99 个不同方法均取得通过证据，其中 62 项真实 MySQL9；不重复计数 | `/tmp/i04-handoff-tests.log`、`/tmp/i04-handoff-preflight-tests.log` |
| 餐饮 App | 3 个文件，17/17 通过 | `/tmp/i04-handoff-app-tests.log` |
| 商家 App 类型 | vue-tsc --noEmit，退出码 0 | `/tmp/i04-handoff-app-types.log` |
| 双端调用→后端合同 | 630 条，628 对齐、2 条原已登记缺口，基线外 0 | `/tmp/i04-handoff-contract.log` |
| UI 清单 | 从仅库存路由快照生成 335 界面；仅暂存该快照产物，不覆盖工作区其他会话产物 | `scripts/gen-ui-catalog.py` 输出 |
| 提交格式 | git diff --cached --check（保留原 CSV CRLF，cr-at-eol），无错误 | Git 输出 |

后端选择集：

- `RetailStockReplenishmentAdmissionNativeMysqlTest` 18、`RetailStockReplenishmentEffectsNativeMysqlTest` 12、`RetailReplenishmentTargetNativeMysqlTest` 12、`FoodStockReplenishmentNativeMysqlTest` 17、`LocalStockPreflightNativeMysqlTest` 3；上述共 62 项 MySQL9。
- `ProductOwnershipWriteBarrierTest`、`StockReplenishmentServiceTest`、`InventoryReplenishmentSourceHttpTest`、`LocalStockControlServiceTest`、`LocalStockPreflightApiTest`、`StockReplenishmentApiTest`、`FoodReplenishmentAdmissionContractTest`、`FoodStockReplenishmentSignedHttpTest`、`RemoteFoodStockReplenishmentPortTest`、`ReplenishmentQueryServiceTest`；合计其余 37 项。

D2-03 开发结束时的一次独立只读复核无 P1/P2 和延期 minor；此后交付操作只做文件拆分、阶段登记、文档和生成产物，不修改运行逻辑。

提交不包含其他会话的账号、经营主体、支付、连锁、套餐、打印或原单处置后续改动。共享文件按 Git index 局部暂存，原工作区内容保留。任务 CSV 只提交 I04 行，其他 24 项沿提交基线逐字节保留。此记录不是目标环境部署/App 真机/166 营业验收证明。

推送前检查补齐：首次 pre-push 正确发现新餐饮补货页未登记进销存 PAGES；已登记并生成第15屏逐项清单，`python3 scripts/gen-inv-ui-inventory.py --check` 通过。属于生成登记修正，无运行逻辑变更。


## 推送闸门的既有阻塞与本批处置

正常 pre-push 已实际执行两次。第一次发现本批新补货页漏登记，已修复，15屏清单通过。第二次依次通过 UI 清单、原型登记、库存清单、运营端功能清单、V2 文档守卫、ops 合同、App 调用合同和6项交付登记测试；随后在“后端无 App 入口”检查中止。

已将父提交 `c58020fa3` 的完整源码导出，运行相同孤儿检查：父提交与本次均有完全相同的27条基线外记录，增加0、减少0。涉及身份、进件、评论和改挂等旧业务，与本批库存无关。明细见[pre-push-baseline.txt](./pre-push-baseline.txt)，本机日志为 `/tmp/i04-handoff-baseline-orphan.log` 与 `/tmp/i04-handoff-push.log`。

不修改或放宽旧业务欠账基线，不声称全局 pre-push 全绿。依据根 MEMORY 与 Java规范§8.4 已确认的增量验证约定，本次保留该既有失败，使用已通过的提交快照编译、99项相关后端测试、17项App测试、类型检查和限定合同结果，单次绕过该全局hook完成授权推送。全量/未运行的后续全局闸门没有取得通过证据；真实环境验收继续按交接后续任务执行。
