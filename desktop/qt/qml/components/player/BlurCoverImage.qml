import QtQuick
import QtQuick.Effects

// 播放页模糊背景的单个图层：封面放大裁切 + 高斯模糊。
// 加载完成后通过 host.onLayerReady(index) 通知宿主编排交叉淡入。
Item {
    id: layer

    property int layerIndex: 0
    property var host: null
    property alias source: bgImage.source

    Image {
        id: bgImage
        anchors.fill: parent
        anchors.margins: -Math.max(parent.width, parent.height) * 0.15
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        cache: true
        smooth: true
        // 背景本来就是糊的，解码小图即可：省内存，放大本身也自带柔化
        sourceSize.width: 320
        sourceSize.height: 320
        visible: false

        onStatusChanged: {
            if (status === Image.Ready && source !== "" && layer.host)
                layer.host.onLayerReady(layer.layerIndex)
        }
    }

    MultiEffect {
        anchors.fill: bgImage
        source: bgImage
        blurEnabled: true
        blur: 1.0
        blurMax: 64
    }
}
