---
description: 发布新版本 — 同步版本号、写提交描述、打 tag 并推送(自动发布 npm、构建 APK、生成 Release changelog)
argument-hint: "[版本号 | major | minor | patch]"
---

# 发布新版本

目标版本:${1:-minor}

按以下步骤执行,完成前不要停下来。

## 1. 查看变更范围

- `git status` 和 `git diff`(含已暂存改动)了解自上次发布以来的全部改动
- `git log --oneline $(git describe --tags --abbrev=0)..HEAD` 列出待发布的提交
- 确认改动范围与本次发布相符;若有无关的临时改动(WIP),先处理或排除,不要混进发布提交
- 决定版本号(semver):bug 修复 → patch,新功能 → minor,破坏性变更 → major;
  顶部已传入版本号则直接使用,未传入则按上述规则自动决定

## 2. 质量检查

- server:`cd server && npm run typecheck && npm test`
- android:`cd app && ./gradlew testDebugUnitTest --no-daemon`

## 3. 同步版本号(目标版本不带 `v` 前缀)

- `server/package.json` 的 `version` 字段
- `app/composeApp/build.gradle.kts` 的 `versionName = "…"`,并把 `versionCode` 加 1
- 若 README 或其他文件里有版本引用,一并更新

## 4. 写提交并提交(核心:提交描述就是 Release 的 changelog)

- 用一条 conventional commit 涵盖所有改动,subject 形如 `feat(server,app): …` 或 `fix(…): …`
- **正文详细描述**:改了哪些模块、为什么改、关键行为变化,可分点列出
- 正文会原样展示在 GitHub Release 页面,请写得像正式发布说明
- 执行 `git add -A && git commit` 提交

## 5. 打 tag 并推送(触发发布)

- `git tag v<目标版本>`
- `git push origin`(当前分支)
- `git push origin v<目标版本>`
- 推送后自动触发两个 workflow:
  - `npm-publish.yml`:发布 npm 包(版本取自 tag)
  - `android-build.yml`:构建 APK 并创建带 changelog 的 GitHub Release

## 6. 验证

- `gh run list --limit 5` 确认两个 workflow 已触发且成功
- 确认 GitHub Release 页面的 body 与提交描述一致、APK 已挂载
- 不要手动创建 Release,workflow 会自动处理
