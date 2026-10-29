# Task60A-A6 永久SQL方言扫描覆盖
Status: IMPLEMENTATION_APPROVED after A5c product 811de0f35c67dd1319e087974cb92d7ace8ded0e. 档位0，依据mainTDD60A AC5。唯一worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation；实施BASE由根提供。

Owned仅 scripts/check-sql-portability.mjs、新 scripts/tests/sql-portability-scope.test.mjs，加本片report/evidence。禁止改SQL/Java/maskSqlNoise/known-*/package/POM/lock/其它script/root文档/部署；不stage/commit/push/merge/子agent，不跑native。实际额外方言债只报告给根，不能appendbaseline或跳目录让绿。

## 现状与选择
根只读准备 /tmp/task60a-a6-root-facts.md。既有扫描只遍历backend，且整份Java先maskSqlNoise导致@Select普通字符串和textblock内SQL完全被遮掉。不能仅添加路径并声称Java覆盖。选择在同一checker抽共用扫描入口，增加静态Java字符串SQL提取后再复用现有SQLmask/唯一RULES；不添加Java AST依赖或实现动态SQL求值。SQL字符串中的字面量/SQL注释继续mask，Java注释与字符字面量先忽略，真实静态SQL字面量不能先被当Java噪音删掉。保持原始文件行号；至少支持普通Java双引号字符串与三引号textblock及基本转义。不承诺跨方法/运行时拼接解析；报告准确边界，不把未解析动态SQL说全覆盖。

## 入口与行为
- 导出 SCAN_ROOTS = Object.freeze(["backend","backend-plugins/shop-industry-beauty"])。
- 导出 scanSqlPortability(root, scanRoots=SCAN_ROOTS)，返回 {hits,fresh,stale,knownCount}；hits保留现有 {file,line,id,side,why,fix,text}，file相对root。共用原十条RULES和maskSqlNoise，不写第二份regex、mock判定或由测试调用私有替代扫描。
- 原CLI默认root仍依据自身文件位置，固定上述两目录，不提供可排除任一目录的argv入口；--check保留fresh或stale时exit1，非check保持原展示模式。脚本导入无扫描/输出/process.exit副作用，仅直接执行运行CLI。
- known文件仍root/backend/known-sql-dialect.txt；key保持文件TAB规则，不加行号，不改规则/掩码/豁免。既有target/node_modules/.git跳过保留；不得跳src/test等新目录。必要目录缺失不得无声地假绿（可明确失败，测试临时仓库须建两根）。
- Java提取函数内置本checker仅负责源码literal/comment范围及必要Javaescape，随后每份SQL payload仍用同一个mask+RULES。行号基于原文件；SQL文字中的注释/字面量不误报，Java //、/* */、字符常量不误当SQL。保留动态边界，不泛化全部Java语义。

## 真实行为测试
所有坏SQL/Java通过测试运行时写自有tmp仓库布局，finally仅清自有目录，不在实际迁移创建坏文件。CLI测试将实际checker和其sql-mask依赖原字节复制到tmp/scripts（root仍由modulepath推导），运行真正node scripts/check-sql-portability.mjs --check；不传人为scanRoots来跳过beauty，不mock进程exit。
- beautySqlAndJavaUseTheSamePortabilityRules：backend和beauty各有.sql、Java注解普通literal和SQLtextblock，含真实 CREATE INDEX IF NOT EXISTS。实际scan返回每份对应file、同rule=create-index-if-not-exists、准确原fileline、fresh。引入另一规则样例可证明非仅测试单regex。
- badBeautySqlFailsCliCheck：backend合法、beauty坏SQL，实际CLI exit1且指出plugin文件/rule；修成合法后exit0。另一个Java-only坏textblock也需CLI exit1，防止只纯函数测Java而CLI漏。
- sqlNoiseDoesNotCreateHits：SQL注释/COMMENT及字符串中的坏示例不报；Java注释/字符及Java SQL payload内SQL注释/SQL字符串不报，真实相邻代码坏SQL仍报，保证不是全遮蔽。引号和转义/原行号都实际断言。
- 原baseline key/stale：tmp带精确known可消除对应fresh；缺命中的known仍stale且--check exit1；known匹配仍按路径/规则不按行号。真实backend known字节不变。
- 导入纯函数不能执行整repo扫描/exit或输出；不通过检查源码字符串镜像自证。
不要过度写实现镜像测试，重点证明生产CLI和实际扫描两根/两种输入经同规则。

## TDD与验证
- [ ] 新测试先取得真实missingbeauty/Java被mask行为RED。必要先抽导出接口/CLIguard但保留backend-only与旧Java mask以取得真正行为失败，缺导出/编译/环境不算RED。
- [ ] 最小实现同文件接口+Java literal范围修复、固定两根，node --test scripts/tests/sql-portability-scope.test.mjs GREEN。
- [ ] node scripts/check-sql-portability.mjs --check 在实际工作树真实执行，记录hits/known/fresh/stale及退出码，不能用非阻断模式。若出现此前漏扫实际违规，不绕过，交根精确位置/原因和最小方案。
- [ ] 消融移除生产SCAN_ROOTS的beauty根→实际CLI负例RED（必要fixture默认scan也红）；恢复逐字。Java提取改回旧mask应使Java实际正反用例RED；恢复同SHA后一次完整Node GREEN即可，不反复运行不变代码。记录真实mutationdiff、counts、hash。
- [ ] 最后源freeze、owned manifest含新文件/完整BASEdiff，真实known文件SHA不变、无额外产品更改。report task-60a-a6-report.md含commands/logs/RED-GREEN/消融恢复、静态Java边界、自审/警告/偏差。根独立spec+quality审查后精确提交。

本片无Java编译/业务接口/数据库/部署操作，不声称全60A/BM04/P1-P4完成。用户已授权连续执行，不重复询问确认。
