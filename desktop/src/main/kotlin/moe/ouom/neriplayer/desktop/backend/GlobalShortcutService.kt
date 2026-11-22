package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.GlobalShortcutsPortal
import moe.ouom.neriplayer.desktop.core.ShortcutAction

/**
 * 全局快捷键（xdg-desktop-portal GlobalShortcuts）。
 *
 * 播放控制类动作交给 [AppContainer.playbackCommands]（一起听房间里当听众时它会转发给房间，
 * 和界面按钮、MPRIS、托盘走同一条路径）；窗口/导航类动作通过 "shortcut" 事件交给 Qt 前端：
 * {action: "<ShortcutAction.id>"}。
 */
class GlobalShortcutService(
    private val container: AppContainer,
    private val server: CoreServer,
) {
    private val portal = GlobalShortcutsPortal { actionId -> handle(actionId) }

    /** 播放控制是 suspend 的（房间转发要等控制结果），而 portal 回调在 D-Bus 线程上，同步不了。 */
    private var scope: CoroutineScope? = null

    val active: Boolean get() = portal.isActive

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch(Dispatchers.IO) {
            runCatching { portal.registerDefaults() }
            log("全局快捷键${if (portal.isActive) "已注册" else "不可用"}")
        }
    }

    fun openConfigure() {
        runCatching { portal.openConfigure() }
    }

    /** 读取系统当前实际生效的全局快捷键（actionId → 触发器文本）；不支持时返回 null。 */
    fun currentGlobalBindings(): Map<String, String>? = runCatching { portal.listShortcuts() }.getOrNull()

    fun stop() {
        runCatching { portal.stop() }
    }

    private fun handle(actionId: String) {
        val action = ShortcutAction.fromId(actionId) ?: return
        val playback = container.playbackCommands
        when (action) {
            ShortcutAction.PLAY_PAUSE -> launchPlayback { playback.togglePlayPause() }
            ShortcutAction.NEXT_SONG -> launchPlayback { playback.next() }
            ShortcutAction.PREVIOUS_SONG -> launchPlayback { playback.previous() }
            // 音量是纯本地状态，房间不仲裁音量，保持直接改播放器
            ShortcutAction.VOLUME_UP -> container.player.setVolume((container.player.volume.value + 0.05f).coerceAtMost(1f))
            ShortcutAction.VOLUME_DOWN -> container.player.setVolume((container.player.volume.value - 0.05f).coerceAtLeast(0f))
            ShortcutAction.SEEK_FORWARD -> launchPlayback { playback.seekBy(5000) }
            ShortcutAction.SEEK_BACKWARD -> launchPlayback { playback.seekBy(-5000) }
            else -> server.broadcastMap("shortcut", mapOf("action" to actionId))
        }
    }

    private fun launchPlayback(command: suspend () -> Unit) {
        val target = scope
        if (target == null) {
            // 还没 start() 就被触发（理论上不会）：宁可不动，也不绕过房间判定改本地播放器
            log("全局快捷键在初始化前触发，已忽略")
            return
        }
        target.launch { runCatching { command() } }
    }
}
