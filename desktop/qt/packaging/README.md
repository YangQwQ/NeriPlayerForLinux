# NeriPlayer Linux 桌面版打包

三种 Linux 安装包由统一脚本构建，共用一份「构建产物 + staging 目录」：

| 格式 | 工具 | 产物（位于 `desktop/qt/build/packages/out/`） |
| --- | --- | --- |
| `.deb` | `dpkg-deb` | `neriplayer_<version>_<arch>.deb` |
| `.rpm` | `rpmbuild` | `neriplayer_<version>_<arch>.rpm`（arch 为 `x86_64`/`aarch64`） |
| `.AppImage` | `appimagetool` | `neriplayer_<version>_<arch>.AppImage` |

## 本地构建

前置依赖：

- 通用：CMake ≥ 3.21、Ninja、Qt6 开发包（Core/Gui/Quick/Qml/Network/Svg/DBus）、
  JDK 17（`JAVA_HOME` 或系统默认 `jlink`）、`gradlew` 可联网拉依赖（或用 `--offline` 缓存）。
- deb：`dpkg-deb`（Debian/Ubuntu 自带）。
- rpm：`rpmbuild`（Fedora/RHEL 系 `rpm-build`，Debian/Ubuntu 上 `apt install rpm`）。
- AppImage：`appimagetool`（从 [AppImageKit releases](https://github.com/AppImage/AppImageKit/releases)
  下载 `appimagetool-x86_64.AppImage` 放到 PATH；运行时需要 `libfuse2`）。

```bash
# 一次构建三种（版本号自动读取 desktop/VERSION；耗时：cmake + gradle 只会跑一次）
desktop/qt/packaging/build-packages.sh

# 只构建某一种（版本同样取自 desktop/VERSION）
desktop/qt/packaging/build-packages.sh --only deb
desktop/qt/packaging/build-packages.sh --only rpm
desktop/qt/packaging/build-packages.sh --only appimage

# 纯修复的修订包：显式传第四段修订号，不改 VERSION
desktop/qt/packaging/build-packages.sh 3.0.0.1 --only deb

# 旧的 build-deb.sh 入口仍然可用，等价于 --only deb
desktop/qt/packaging/build-deb.sh 2.0.0
```

构建流程：

1. CMake/Ninja 构建 Qt Release 前端，`DESTDIR` 安装进 `build/packages/stage`；
2. `gradlew backendJar` 产出 `neriplayer-core.jar`，`jlink` 生成精简 JRE；
3. 把启动器、desktop 文件、图标、LICENSE、QML 一起放进 staging；
4. 按格式分别打包：deb 由 `deb-control.in` 模板生成 `DEBIAN/control`，
   rpm 由 `neriplayer.spec.in` 生成 spec，AppImage 由 staging 拼出 `AppDir`。

可用环境变量（CI 中用于覆盖工具位置）：

- `PACKAGING_BUILD_DIR`：构建/产物根目录，默认 `desktop/qt/build/packages`。
- `JAVA_HOME` / `JLINK`：jlink 路径。
- `RPMBUILD`：`rpmbuild` 路径；`APPIMAGETOOL`：`appimagetool` 路径。

## 依赖假设

- 应用本身自带精简 JRE（jlink），所以不依赖系统 Java；但 deb/rpm 仍把
  `default-jre-headless` / JRE 列为 `Recommends`，兼容不自带运行时的旧版本。
- **三种格式都依赖宿主的 Qt6/QML 运行库**（含 Qt Quick Controls、DBus、Svg、
  Wayland 客户端及其 QML 模块），不是完全静态自包含。deb/rpm 通过包管理器
  声明这些依赖；AppImage 只携带应用自身文件，**不会**在缺少 Qt6 的机器上自动可用。
- 音频解码/下载依赖宿主的 `ffmpeg` 与 `yt-dlp`（deb/rpm 列为 `Recommends`，
  AppImage 用户需自行安装）；托盘/系统主题依赖 GTK3/GObject 与中文字体。
- deb 的 `Depends`/`Recommends` 在 `build-packages.sh` 顶部集中维护；
  rpm 的 `Requires`/`Recommends` 在 `neriplayer.spec.in` 里按 RPM 包名单独维护，
  修改依赖时两处都要同步。

## CI 触发

- `.github/workflows/desktop-packages.yml`：push 到 `compose-desktop` 或 PR 且改动
  `desktop/**` / 该 workflow 自身，以及手动 `workflow_dispatch`，在
  `ubuntu-22.04` 上构建三种包，校验后上传为 GitHub Actions artifact
  （含 `SHA256SUMS.txt`）。
- `.github/workflows/desktop-packages-release.yml`：tag `v*` 推送（或 dispatch
  传入已存在的 tag）时构建并在 GitHub Release 中创建/更新资源；先校验
  deb/rpm/AppImage/SHA256SUMS 齐全，再用 `gh release` 上传（重复 tag 用 `--clobber`）。

> 注意：本机没有 `rpmbuild` / `appimagetool`，rpm 与 AppImage 只做了脚本与
> 模板层面的自检，未在本机完整构建验证；CI 会安装这些工具并做一次真实构建。
