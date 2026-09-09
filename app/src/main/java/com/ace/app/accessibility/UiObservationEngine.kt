package com.ace.app.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class UiObservationEngine {

    fun captureSnapshot(): UiSnapshot {
        val service = AceAccessibilityService.getInstance() ?: return UiSnapshot()
        val root = service.rootInActiveWindow ?: return UiSnapshot()

        val pkgName = root.packageName?.toString() ?: ""
        val nodeList = mutableListOf<UiNode>()
        traverseNodesRecursive(root, nodeList)

        val snapshot = UiSnapshot(
            packageName = pkgName,
            nodeCount = nodeList.size,
            nodes = nodeList
        )

        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=$pkgName nodes=${nodeList.size}")
        return snapshot
    }

    private fun traverseNodesRecursive(
        node: AccessibilityNodeInfo,
        outList: MutableList<UiNode>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val uiNode = UiNode(
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            hintText = node.hintText?.toString() ?: "",
            resourceId = node.viewIdResourceName?.toString() ?: "",
            className = node.className?.toString() ?: "",
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            boundsInScreen = rect.toShortString(),
            nodeRef = node
        )

        outList.add(uiNode)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodesRecursive(child, outList)
        }
    }
}
