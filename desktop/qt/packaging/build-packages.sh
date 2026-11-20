#!/usr/bin/env bash
# 统一构建 NeriPlayer Linux 桌面版安装包（.deb / .rpm / .AppImage）。
#
# 一次构建 Qt 前端 + Kotlin 后端 + jlink 运行时，产出共用的 staging 目录，
# 再分别交给 dpkg-deb / rpmbuild / appimagetool 打包。三种格式共享同一份
# 负载，避免互相漂移。
#
# 用法：
#   build-packages.sh <version> [--only deb|rpm|appimage]
#   build-packages.sh 2.0.0                 # 三种都构建
#   build-packages.sh 2.0.0 --only deb      # 只构建 deb（本地常用）
#
# 产物：desktop/qt/build/packages/out/neriplayer_<version>_<arch>.<ext>
#
# 依赖：cmake / ninja / Qt6 开发包 / JDK 17（JAVA_HOME 或系统默认）；
#       按需：dpkg-deb（deb）、rpmbuild（rpm）、appimagetool（AppImage）。
#       CI 里可通过 env 覆盖工具路径，例如 RPMBUILD=/usr/bin/rpmbuild。
set -euo pipefail

PKG_NAME="neriplayer"
HOMEPAGE="https://github.com/YangQwQ/NeriPlayerForLinux"

# Gradle 8.9 不能运行在 JDK 25 上，系统默认 java 可能是新版本；后端与 jlink
# 都固定用 JDK 17（可用环境变量覆盖）。
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

# deb Depends / Recommends 的唯一来源。rpm 依赖在 neriplayer.spec.in 里单独
# 按发行版包名维护；改动依赖时两处都要同步（见 packaging/README.md）。
DEB_DEPENDS='libqt6core6 | libqt6core6t64, libqt6gui6 | libqt6gui6t64, libqt6qml6 | libqt6qml6t64,
 liblayershellqtinterface6, layer-shell-qt,
 libqt6quick6 | libqt6quick6t64, libqt6network6 | libqt6network6t64, libqt6svg6, libqt6dbus6,
 libqt6opengl6 | libqt6opengl6t64, libqt6waylandclient6 | libqt6waylandclient6t64, qt6-wayland,
 qml6-module-qtquick, qml6-module-qtquick-window, qml6-module-qtquick-controls,
 qml6-module-qtquick-templates, qml6-module-qtquick-layouts, qml6-module-qtquick-dialogs,
 qml6-module-qtqml, qml6-module-qtqml-models, qml6-module-qtcore, qml6-module-qtnetwork,
 python3, python3-gi, gir1.2-glib-2.0, gir1.2-gtk-3.0, fonts-noto-cjk'
DEB_RECOMMENDS='ffmpeg, yt-dlp, xdg-utils,
 default-jre-headless | java17-runtime-headless | java-runtime-headless'

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
}

VERSION=""
only=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --only)
            shift
            only="${1:-}"
            case "$only" in
                deb|rpm|appimage) ;;
                *) echo "错误：--only 接受 deb|rpm|appimage，收到 '$only'" >&2; exit 2 ;;
            esac
            ;;
        --only=*)
            only="${1#--only=}"
            case "$only" in
                deb|rpm|appimage) ;;
                *) echo "错误：--only 接受 deb|rpm|appimage，收到 '$only'" >&2; exit 2 ;;
            esac
            ;;
        -*) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
        *)
            if [ -n "$VERSION" ]; then echo "错误：重复的版本号 '$1'" >&2; exit 2; fi
            VERSION="$1"
            ;;
    esac
    shift
done
VERSION="${VERSION:-2.0.0}"

PACKAGING_DIR="$(cd "$(dirname "$0")" && pwd)"
QT_DIR="$(cd "$PACKAGING_DIR/.." && pwd)"
DESKTOP_DIR="$(cd "$QT_DIR/.." && pwd)"
BUILD_DIR="${PACKAGING_BUILD_DIR:-$QT_DIR/build/packages}"
STAGE="$BUILD_DIR/stage"
OUT_DIR="$BUILD_DIR/out"
APPDIR="$BUILD_DIR/AppDir"
JLINK_MODULES='java.base,java.logging,java.net.http,java.desktop,java.naming,java.management,java.instrument,jdk.crypto.ec,jdk.security.auth,jdk.unsupported,jdk.zipfs'
JLINK="${JLINK:-${JAVA_HOME:+$JAVA_HOME/bin/}jlink}"
RPMBUILD="${RPMBUILD:-rpmbuild}"
APPIMAGETOOL="${APPIMAGETOOL:-appimagetool}"

ARCH="$(dpkg --print-architecture 2>/dev/null || echo amd64)"
case "$ARCH" in
    amd64) RPM_ARCH=x86_64 ;;
    arm64) RPM_ARCH=aarch64 ;;
    *)     RPM_ARCH="$ARCH" ;;
esac

echo "==> 版本 $VERSION / 架构 $ARCH (rpm: $RPM_ARCH) / 输出 $OUT_DIR"
mkdir -p "$OUT_DIR"

# ---------- 三种格式共用的构建阶段 ----------
stage_payload() {
    echo "==> 准备 staging 目录 $STAGE"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/DEBIAN" \
             "$STAGE/usr/bin" \
             "$STAGE/usr/lib/neriplayer" \
             "$STAGE/usr/share/neriplayer" \
             "$STAGE/usr/share/applications" \
             "$STAGE/usr/share/icons/hicolor/128x128/apps" \
             "$STAGE/usr/share/icons/hicolor/256x256/apps" \
             "$STAGE/usr/share/doc/$PKG_NAME"

    echo "==> 构建 Qt 前端（Release）"
    cmake -S "$QT_DIR" -B "$BUILD_DIR/cmake" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr >/dev/null
    cmake --build "$BUILD_DIR/cmake" -j"$(nproc)"
    DESTDIR="$STAGE" cmake --install "$BUILD_DIR/cmake" >/dev/null

    echo "==> 构建后端 jar"
    # 不能加 --offline：CI 的全新 runner 没有 Gradle 缓存，离线模式解析不到 Kotlin 插件。
    ( cd "$DESKTOP_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew -q backendJar )
    install -m 644 "$DESKTOP_DIR/build/libs/neriplayer-core.jar" \
        "$STAGE/usr/lib/neriplayer/neriplayer-core.jar"

    echo "==> jlink 精简运行时"
    rm -rf "$STAGE/usr/lib/neriplayer/runtime"
    "$JLINK" \
        --add-modules "$JLINK_MODULES" \
        --strip-debug --no-header-files --no-man-pages --compress=2 \
        --output "$STAGE/usr/lib/neriplayer/runtime"

    echo "==> 安装启动脚本 / desktop / 图标 / 许可证"
    cat > "$STAGE/usr/bin/neriplayer" <<'LAUNCHER'
#!/bin/sh
# Qt 前端会自动在 ../lib/neriplayer 下找 neriplayer-core.jar 与自带 JRE
exec /usr/lib/neriplayer/neriplayer "$@"
LAUNCHER
    chmod 755 "$STAGE/usr/bin/neriplayer"

    install -m 644 "$QT_DIR/packaging/neriplayer.desktop" \
        "$STAGE/usr/share/applications/neriplayer.desktop"
    install -m 644 "$DESKTOP_DIR/packaging/neriplayer-128.png" \
        "$STAGE/usr/share/icons/hicolor/128x128/apps/neriplayer.png"
    install -m 644 "$DESKTOP_DIR/packaging/neriplayer-256.png" \
        "$STAGE/usr/share/icons/hicolor/256x256/apps/neriplayer.png"
    cp "$DESKTOP_DIR/packaging/LICENSE" "$STAGE/usr/share/doc/$PKG_NAME/copyright"

    # 目录统一 755：dpkg-deb --root-owner-group 会强制 root/root，
    # 这里避免把源码目录里粘连的组写权限带进安装包。
    find "$STAGE" -type d -exec chmod 755 {} +
}

# 从模板生成 deb control。DEB_DEPENDS/DEB_RECOMMENDS 含多行与竖线，
# 用逐行替换而不是 sed，避免分隔符与特殊字符冲突。
write_deb_control() {
    local installed_size line
    installed_size="$(du -sk "$STAGE" | cut -f1)"
    while IFS= read -r line; do
        case "$line" in
            'Depends: @DEB_DEPENDS@')     printf 'Depends: %s\n' "$DEB_DEPENDS" ;;
            'Recommends: @DEB_RECOMMENDS@') printf 'Recommends: %s\n' "$DEB_RECOMMENDS" ;;
            *)
                line="${line//@PKG_NAME@/$PKG_NAME}"
                line="${line//@VERSION@/$VERSION}"
                line="${line//@ARCH@/$ARCH}"
                line="${line//@INSTALLED_SIZE@/$installed_size}"
                line="${line//@HOMEPAGE@/$HOMEPAGE}"
                printf '%s\n' "$line"
                ;;
        esac
    done < "$PACKAGING_DIR/deb-control.in" > "$STAGE/DEBIAN/control"
}

build_deb() {
    command -v dpkg-deb >/dev/null || { echo "缺少 dpkg-deb" >&2; return 1; }
    write_deb_control
    install -m 755 "$PACKAGING_DIR/deb-postinst" "$STAGE/DEBIAN/postinst"
    install -m 755 "$PACKAGING_DIR/deb-postrm" "$STAGE/DEBIAN/postrm"
    local deb="$OUT_DIR/${PKG_NAME}_${VERSION}_${ARCH}.deb"
    echo "==> 打包 .deb → $deb"
    dpkg-deb --root-owner-group -Zxz --build "$STAGE" "$deb" >/dev/null
    dpkg-deb --info "$deb" >/dev/null   # 基本自检：control 能被 dpkg 解析
    echo "完成：$deb"
}

build_rpm() {
    command -v "$RPMBUILD" >/dev/null \
        || { echo "缺少 rpmbuild（可用 RPMBUILD=... 覆盖）" >&2; return 1; }
    local spec="$BUILD_DIR/${PKG_NAME}.spec"
    sed -e "s|@VERSION@|$VERSION|g" \
        -e "s|@ARCH@|$RPM_ARCH|g" \
        -e "s|@RPM_BUILDROOT@|$STAGE|g" \
        "$PACKAGING_DIR/neriplayer.spec.in" > "$spec"

    local work="$BUILD_DIR/rpmbuild"
    rm -rf "$work"
    mkdir -p "$work/BUILD" "$work/RPMS" "$work/SPECS" "$work/SOURCES" \
             "$work/SRPMS" "$work/BUILDROOT"
    "$RPMBUILD" -bb --define "_topdir $work" "$spec"

    local rpm
    rpm="$(find "$work/RPMS" -name "${PKG_NAME}-${VERSION}-*.rpm" -print -quit)"
    [ -n "$rpm" ] || { echo "rpmbuild 未产出 RPM" >&2; return 1; }
    local dest="$OUT_DIR/${PKG_NAME}_${VERSION}_${RPM_ARCH}.rpm"
    cp "$rpm" "$dest"
    echo "完成：$dest"
}

build_appimage() {
    command -v "$APPIMAGETOOL" >/dev/null \
        || { echo "缺少 appimagetool（可用 APPIMAGETOOL=... 覆盖）" >&2; return 1; }
    echo "==> 准备 AppDir $APPDIR"
    rm -rf "$APPDIR"
    mkdir -p "$APPDIR"
    # AppDir 布局：二进制与 jar / runtime / QML 平铺，AppRun 自行推导目录。
    cp -a "$STAGE/usr/lib/neriplayer/." "$APPDIR/"
    cp -a "$STAGE/usr/share/neriplayer/qml" "$APPDIR/qml"
    install -m 644 "$DESKTOP_DIR/packaging/neriplayer-256.png" "$APPDIR/neriplayer.png"
    install -m 644 "$QT_DIR/packaging/neriplayer.desktop" "$APPDIR/neriplayer.desktop"
    cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
# AppImage 不经过系统安装路径，所有资源都在 AppDir 内；显式指定 QML 目录让
# Qt 前端总能找到随包 QML。后端 jar / jlink JRE 与二进制同目录，前端自动发现。
HERE="$(dirname "$(readlink -f "$0")")"
export NERIPLAYER_QML_DIR="$HERE/qml"
exec "$HERE/neriplayer" "$@"
APPRUN
    chmod 755 "$APPDIR/AppRun" "$APPDIR/neriplayer"

    local appimage="$OUT_DIR/${PKG_NAME}_${VERSION}_${RPM_ARCH}.AppImage"
    echo "==> 打包 .AppImage → $appimage"
    "$APPIMAGETOOL" "$APPDIR" "$appimage"
    chmod 755 "$appimage"
    echo "完成：$appimage"
    echo "注意：该 AppImage 依赖宿主 Qt6/QML 运行库，不是完全静态自包含。"
}

# 三种格式共用同一份 stage，这里统一构建一次。
stage_payload

case "$only" in
    ""|deb)   build_deb ;;
    rpm)      build_rpm ;;
    appimage) build_appimage ;;
esac

echo "==> 产物列表"
ls -lh "$OUT_DIR"
