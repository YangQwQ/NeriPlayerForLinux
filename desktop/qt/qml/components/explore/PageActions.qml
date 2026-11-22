import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../library"

// 页面侧播放 / 设置写入的兼容层。
//
// 背景：globals/ 下的全局对象一旦内部引用 Api，就可能因为同目录隐式导入把 "Player"/"Settings"
// 解析成 QML 类型而不是 context property，跨对象调用会抛 TypeError（Player.setQueue / Settings.set
// 全部不可用）。主程序已改用 *Global.qml 命名并先注册 Api；这里仍保留一次兜底：
// 优先走全局封装，抛错时直接调用后端命令，避免页面功能因全局对象实现变化而失效。
QtObject {
    id: actions

    /**
     * 待用户确认的「一起听房间内整队操作」请求。
     *
     * 房间里作为听众时点「播放全部 / 换一张歌单」，后端先回 `needsConfirm`：
     *  - 追加：逐首追加到房间队列末尾（保留当前队列、不打断别人）；
     *  - 替换：带完整新队列发 REQUEST_SET_TRACK，所有成员一起切歌。
     * 由这里弹三选一确认框，用户选完再带 `mode` 调一次 player.setQueue。
     */
    property var pendingQueue: null

    /** 追加请求完成后通知页面（页面可据此刷新或补提示）。 */
    signal roomAppendRequested(var songs, int startIndex)

    /**
     * 用整张列表设置播放队列并从 index 开始播放（播放全部 / 点列表某一行 / 换一张歌单）。
     *
     * 统一走后端命令：只有后端知道当前是不是「一起听房间里的听众」。非房间场景后端
     * 执行的就是原来的 player.setQueue，行为不变、也不会弹任何确认框。
     */
    function playSongs(songs, index) {
        const list = songs || []
        if (list.length === 0)
            return
        const start = index === undefined || index === null ? 0 : index
        let request = null
        try {
            request = Api.call("player.setQueue", {
                                   "songs": list,
                                   "startIndex": start,
                                   "autoPlay": true
                               })
        } catch (error) {
            // Api 不可用时回落到全局封装（见文件头说明）；此时没有房间预检能力
        }
        if (!request) {
            try {
                Player.setQueue(list, start, true)
            } catch (error) {
                Toast.show(I18n.tr("播放失败"), "error")
            }
            return
        }
        request.then(function(result) {
            // 非房间场景 / 控制者：后端已经直接换了队列，无需任何确认
            if (!result || result.needsConfirm !== true)
                return
            actions.pendingQueue = {
                "songs": list,
                "startIndex": start,
                "appendable": Number(result.appendable || 0),
                "skipped": Number(result.skipped || 0)
            }
            roomQueueDialog.openFor(
                I18n.tr("一起听房间中播放全部"),
                actions.queueMessage(actions.pendingQueue))
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("播放失败"), "error")
        })
    }

    /** 确认框正文：说清两种模式的区别，并交代无法加入的歌曲数。 */
    function queueMessage(pending) {
        if (!pending)
            return ""
        let text = I18n.tr("你是听众。追加：保留房间现有队列，把当前歌单的 {n} 首歌曲加到队尾，不打断任何人。替换：清空房间现有队列，其他成员会跟着切到新歌单的第一首。")
                .replace("{n}", pending.appendable)
        if (pending.skipped > 0) {
            text += "\n\n" + I18n.tr("其中 {n} 首歌曲无法加入一起听，将被跳过。")
                    .replace("{n}", pending.skipped)
        }
        return text
    }

    /** 用户选择「追加」：后端降级为逐首追加到房间队尾。 */
    function confirmAppend() {
        const pending = actions.pendingQueue
        actions.pendingQueue = null
        if (!pending)
            return
        Api.call("player.setQueue", {
                     "songs": pending.songs,
                     "startIndex": pending.startIndex,
                     "autoPlay": true,
                     "mode": "append",
                     "confirmAppend": true
                 }).then(function(result) {
            const count = result && result.appended !== undefined ? Number(result.appended) : pending.appendable
            Toast.show(I18n.tr("已将 {n} 首歌曲追加到房间队列末尾").replace("{n}", count), "info")
            actions.roomAppendRequested(pending.songs, pending.startIndex)
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("添加到房间队列失败"), "error")
        })
    }

    /** 用户选择「替换」：带 mode=replace 调后端，整队替换房间队列并让所有成员切歌。 */
    function confirmReplace() {
        const pending = actions.pendingQueue
        actions.pendingQueue = null
        if (!pending)
            return
        Api.call("player.setQueue", {
                     "songs": pending.songs,
                     "startIndex": pending.startIndex,
                     "autoPlay": true,
                     "mode": "replace"
                 }).then(function(result) {
            const count = result && result.replaced !== undefined ? Number(result.replaced) : pending.appendable
            Toast.show(I18n.tr("已替换房间队列（{n} 首歌曲），其他成员会跟着切歌").replace("{n}", count), "info")
            actions.roomAppendRequested(pending.songs, pending.startIndex)
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("替换房间队列失败"), "error")
        })
    }

    /**
     * 点一首「零散单曲」（首页 / 探索页推荐栏目里的卡片、竖向行）。
     *
     * 与「播放全部」的 [playSongs] 语义区分开：**不替换播放队列**，而是把这首歌插到正在
     * 播放的下一首并立刻切过去——用户点某张歌曲卡就是想听那一首，用整个栏目替换队列会让
     * 点第 5 首和点「播放全部」看起来一模一样。当前没有在播的歌时直接开播这一首。
     *
     * 一起听房间里作为听众时只「加入房间队列」，不自动切歌：插歌由后端转成房间队列请求
     * （插到房间当前曲目之后），但切歌会打断房间里所有人的收听，得由控制者仲裁，所以这里
     * 只提示已加入房间队列（想切过去可以用播放控制栏的下一首，那条转发已经存在）。
     */
    function playSingle(song) {
        if (!song)
            return
        // 点的就是当前这首：暂停态恢复播放，不重复往队列里插一条
        const current = Player.song
        if (current && current.key === song.key) {
            if (!Player.playing)
                Player.play()
            return
        }
        let request = null
        const hadSong = Player.hasSong
        try {
            request = Api.call("player.enqueueNext", { "songs": [song] })
        } catch (error) {
            // Api 不可用时回落到全局封装（见文件头说明）；此时没有房间判定能力
        }
        if (!request) {
            try {
                Player.enqueueNext([song])
                // 队列原本为空时后端已经直接开播这一首，再 next 只会把它重播一遍
                if (hadSong)
                    Player.next()
            } catch (error) {
                Toast.show(I18n.tr("播放失败"), "error")
            }
            return
        }
        request.then(function(result) {
            // 房间里这次只进房间队列；本地插入马上会被房态覆盖，所以不能在这里再切歌
            if (result && result.roomQueue) {
                Toast.show(I18n.tr("已添加到房间队列"), "info")
                return null
            }
            // 按后端返回的最新队列定位刚插进去的那一份：从当前曲目往后找第一个同 key 的条目，
            // 而不是从队列头部找——歌要是本来就在队列更前面，头部那份是旧的（不该跳回去）。
            // 队列本来为空时后端 enqueueNext 直接开播这首歌（当前下标就指向它），
            // 往后找不到，于是回退到「任何一份」= 当前曲目，自然跳过，不用前端先猜后端在不在播。
            return Api.call("player.queue").then(function(queue) {
                const songs = queue && queue.songs ? queue.songs : []
                const currentIndex = queue && queue.index !== undefined ? Number(queue.index) : -1
                let target = -1
                for (let i = Math.max(0, currentIndex + 1); i < songs.length; i++) {
                    if (songs[i] && songs[i].key === song.key) {
                        target = i
                        break
                    }
                }
                if (target < 0) {
                    for (let i = 0; i < songs.length; i++) {
                        if (songs[i] && songs[i].key === song.key) {
                            target = i
                            break
                        }
                    }
                }
                if (target < 0 || target === currentIndex)
                    return null
                return Api.call("player.playAt", { "index": target })
            })
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("播放失败"), "error")
        })
    }

    /**
     * 把歌曲插到当前播放的下一首。
     *
     * 一起听房间里作为听众时，后端会把它自动转发成房间队列请求（插到房间当前曲目之后），
     * 返回值带 `roomQueue=true`；此时提示必须说「已添加到房间队列」，否则是在撒谎——
     * 本地队列马上会被房间同步覆盖，用户看到的却是「已添加到下一首」。
     */
    function enqueueNext(songs) {
        const list = songs || []
        if (list.length === 0)
            return
        let request = null
        try {
            request = Api.call("player.enqueueNext", { "songs": list })
        } catch (error) {
            // Api 不可用时回落到全局封装（见文件头说明）；此时拿不到房间标记
        }
        if (!request) {
            try {
                Player.enqueueNext(list)
                Toast.show(I18n.tr("已添加到下一首"))
            } catch (error) {
                Toast.show(I18n.tr("添加失败"), "error")
            }
            return
        }
        request.then(function(result) {
            if (result && result.roomQueue)
                Toast.show(I18n.tr("已添加到房间队列"), "info")
            else
                Toast.show(I18n.tr("已添加到下一首"))
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("添加失败"), "error")
        })
    }

    /** 写单个设置项；成功后同步 Settings.values，让依赖设置的界面立刻刷新。 */
    function saveSetting(key, value) {
        try {
            Settings.set(key, value)
            return
        } catch (error) {
            // 回落到底层命令（见文件头说明）
        }
        const patch = {}
        patch[key] = value
        Api.call("settings.set", { "patch": patch }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : I18n.tr("保存设置失败"), "error")
        })
    }

    /**
     * 三选一确认框：取消 / 追加 / 替换。
     *
     * QtObject 本身挂不了可视子项，但把 Dialog 声明成属性就能正常弹出：Popup 的父项是
     * `Overlay.overlay`（窗口的 overlay），由窗口承载，不依赖声明位置的视觉父项。
     */
    property Dialog roomQueueDialog: Dialog {
        id: roomQueueDlg

        property string titleText: I18n.tr("一起听房间中播放全部")
        property string messageText: ""

        parent: Overlay.overlay
        anchors.centerIn: parent
        modal: true
        width: 460
        padding: Theme.spacingXl
        closePolicy: Popup.CloseOnEscape

        function openFor(title, message) {
            roomQueueDlg.titleText = title || I18n.tr("一起听房间中播放全部")
            roomQueueDlg.messageText = message || ""
            roomQueueDlg.open()
        }

        background: Rectangle {
            radius: Theme.radiusXxl
            color: Theme.surfaceContainerHigh
            implicitWidth: 460
            implicitHeight: dlgColumn.implicitHeight + Theme.spacingXl * 2
        }

        contentItem: ColumnLayout {
            id: dlgColumn
            spacing: Theme.spacingLg

            Text {
                Layout.fillWidth: true
                text: roomQueueDlg.titleText
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
            }

            Text {
                Layout.fillWidth: true
                text: roomQueueDlg.messageText
                wrapMode: Text.Wrap
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: Theme.spacingSm

                Item { Layout.fillWidth: true; implicitHeight: 1 }

                LibraryButton {
                    text: I18n.tr("取消")
                    onClicked: roomQueueDlg.close()
                }

                LibraryButton {
                    text: I18n.tr("追加到房间队列")
                    onClicked: {
                        roomQueueDlg.close()
                        actions.confirmAppend()
                    }
                }

                LibraryButton {
                    text: I18n.tr("替换房间队列")
                    filled: true
                    onClicked: {
                        roomQueueDlg.close()
                        actions.confirmReplace()
                    }
                }
            }
        }
    }
}
