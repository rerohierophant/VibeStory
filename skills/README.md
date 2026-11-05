# Skill 文件库

目录是唯一可编辑来源，不需要在 Java 中注册。每个 Skill 使用 `<name>/SKILL.md`：

```markdown
---
name: suspense-script
description: 按悬疑短剧节奏整理剧本，保留剧情线索
roles: [SCRIPT]
---

先读取本集输入与上版产物，保留人物、主要台词与线索。
按照开场悬念、转折与收尾钩子整理，提交草稿，按工具校验问题继续修复。
```

name 与目录名一致，使用小写字母、数字与连字符，最长 64 字符。roles 为 MAIN、SCRIPT、STORYBOARD 的非空子集。
description 最多 300 字符，SKILL.md 为 UTF-8，最多 64 KiB。正文非空。当前运行器只加载该文件，不执行目录里的脚本。

仓库根的内置目录由本机维护者编辑。个人 Skill 位于 `users/<用户 ID>/<name>/SKILL.md`，可新增，也可覆盖同名内置版本。
个人目录不提交 Git。主 Agent 的 Skill 编辑工具与 `/api/skills` 接口读写这一份文件，修改内置项时产生个人覆盖。
人也可以直接用编辑器、脚本新增或修改相应文件；下一次目录扫描即可发现，不需要重启或重新编译。

`SKILLS_ROOT` 指定根目录，默认仓库 `./skills`。API 与 Worker 使用同一绝对路径；从其他目录运行 jar 时须显式配置。
每次运行保存可见文件的正文、元数据与哈希快照，后续编辑只影响新运行。工具权限与输出校验仍由后端控制。

程序接口：GET `/api/skills` 列表；GET `/api/skills/{name}` 读取；PUT 同一路径保存 `{document, expectedVersion}`。
新建使用空 expectedVersion，修改使用读取到的版本。相同正文重复保存可重放；版本变化且正文不同时返回 409。
