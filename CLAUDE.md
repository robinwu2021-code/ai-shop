# ai-shop · 会话须知

> 只写**每个会话都必须遵守、且靠记性会漏**的几条。其余约定在 `docs/` 里，不复制到这儿。

## 改了界面，就要更新界面清单

三端所有页面有一份唯一索引：`docs/technical/design/ui-catalog.json`
（可视化版：[三端界面清单](https://claude.ai/code/artifact/7438f613-7e28-4093-b244-bd80fb13aaa5)）。

**下面任何一件事做完，都要重跑生成器并把 JSON 一起提交：**

- 新增 / 删除页面（`b-app|c-app/src/pages.json`）
- 改页面标题（`pages.json` 或 `<sh-scaffold title-key>` 指向的词条）
- 运营端加菜单或子功能（`ops-web/lib/nav.ts`）
- 画了新的界面原型（在 `scripts/gen-ui-catalog.py` 的 `PROTOTYPES` 里登记；
  页面落地后从那儿删掉，它会自动从 `pages.json` 里出现）

```bash
python3 scripts/gen-ui-catalog.py          # 重新生成
python3 scripts/gen-ui-catalog.py --check  # 只校验（pre-push 会自动跑）
```

**`pre-push` 里有闸门**：清单与代码对不上就推不上去，并会直接列出差在哪一条
（新增 / 删除 / 改名）。它是纯读文件，几十毫秒，不要因为「这次只改了一行」而跳过。

原型稿也在清单里：每条目的「原型」链接直接跳到设计稿对应那一屏，
「预览」链接跳本机 dev server 的那一页。**新画的原型要挂锚点**，
在生成器的 `PROTO_ANCHORS` 里登记路由 → `#sNN`，清单才点得进去。

## 共享工作区

这个目录常有多个会话同时在改。提交前 `git diff HEAD -- <file>` 自己读一遍，
只提交自己认得的行；`git add <目录>` 与 `git checkout <共享文件>` 都会伤到别人。
