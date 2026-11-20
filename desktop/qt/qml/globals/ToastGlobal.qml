import QtQuick

// 轻提示队列（对应 Compose 版的 Snackbar / player.messages）。
QtObject {
    id: toast

    property var items: []
    property int durationMs: 3600
    /** 同文本去重窗口：短时间内重复的相同提示只显示一次。 */
    property int dedupeMs: 500
    property string _lastText: ""
    property real _lastShownAt: 0

    function show(text, kind) {
        if (!text)
            return
        const value = String(text)
        // 同一文本在去重窗口内重复到达时不重复弹出（防止事件重复投递/重复广播）
        const now = Date.now()
        if (value === toast._lastText && (now - toast._lastShownAt) < toast.dedupeMs)
            return
        toast._lastText = value
        toast._lastShownAt = now
        const item = {
            "id": _nextId++,
            "text": value,
            "kind": kind || "info",
            "expiresAt": now + toast.durationMs
        }
        items = items.concat([item])
    }

    function dismiss(id) {
        items = items.filter(function(item) { return item.id !== id })
    }

    function clear() {
        items = []
    }

    property int _nextId: 1

    property Timer cleanupTimer: Timer {
        interval: 250
        repeat: true
        running: toast.items.length > 0
        onTriggered: {
            const now = Date.now()
            toast.items = toast.items.filter(function(item) { return item.expiresAt > now })
        }
    }

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "toast")
                toast.show(data.message, data.kind)
        }
    }
}
