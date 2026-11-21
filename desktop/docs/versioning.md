# 桌面版版本号规则

## 单一事实来源

版本号只写在 [`desktop/VERSION`](../VERSION) 一个文件里（一行，如 `3.0.0`）：

- `build-packages.sh` 不带参数时读取它作为包名版本；也可显式传入版本号来打**修订版**包。
- Gradle 的 `project.version` 读取同一个文件，并生成 `BuildVersion.kt` 里的 `APP_VERSION`
  常量，供 `app.bootstrap` 的 `appVersion` 与设置快照导出共用。
- 前端「设置 → 关于」显示的版本来自 bootstrap，不再硬编码。

因此不存在「包名 3.0.0、界面里却显示 1.4.7」这类漂移。

## 何时递增

采用 `主版本.次版本.修订号`（语义化三段），必要时追加第四段构建修订号：

| 改动类型 | 版本号动作 | 例子 |
| --- | --- | --- |
| 新增用户可见功能 / 行为改变 | 递增次版本（大改递增主版本），修订号归零 | `3.0.0` → `3.1.0` |
| 纯 bug 修复、文案补齐、重构、提交整理 | **不递增**；如需区分构建，加第四段修订号 | `3.0.0` → `3.0.0.1` |
| 仅文档 / CI 改动 | 不递增 | 不变 |

**原则**：没有实际新功能时不递增版本。连续打多个 fix-only 包时用第四段区分即可，
不要把 `3.0.0` 一路刷到 `3.9.9`。

## 发版流程

```bash
# 常规功能发版：先在 desktop/VERSION 改好版本，再直接打包（脚本自动读取）
desktop/qt/packaging/build-packages.sh --only deb

# 纯修复的修订包：保持 VERSION 不变，显式追加第四段
desktop/qt/packaging/build-packages.sh 3.0.0.1 --only deb

# 三种格式一起打
desktop/qt/packaging/build-packages.sh
```

CI 的 Release 工作流从 `v*` tag 取版本号（`v3.0.0` → `3.0.0`），tag 只在功能发版时打。
