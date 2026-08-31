package com.waauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * WhatsApp UI 자동 조작 서비스
 * - 텍스트 전송: 전송 버튼 자동 클릭
 * - 사진 전송: 첨부 → 갤러리 → 이미지 선택 → 캡션 → 전송 상태 머신
 */
class WAAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WAAutoService"
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        // 텍스트 전송
        var pendingMessage: String? = null
        var isReadyToSend: Boolean = false
        var instance: WAAccessibilityService? = null

        // 사진 전송 상태 머신 – 상수 먼저, var는 나중에
        const val STEP_ATTACH  = 0   // 첨부(클립) 버튼 클릭
        const val STEP_GALLERY = 1   // 갤러리 메뉴 클릭
        const val STEP_IMAGE   = 2   // 첫 번째 이미지 선택
        const val STEP_CAPTION = 3   // 캡션(메시지) 입력
        const val STEP_SEND    = 4   // 전송 버튼 클릭

        var photoMode: Boolean = false
        var photoStep: Int = 0       // STEP_ATTACH(=0)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Runnable 참조를 보관해 중복 스케줄 방지
    private val photoRunnable = Runnable { attemptPhotoStep() }
    private val sendRunnable  = Runnable { attemptSend() }

    // 같은 스텝 재실행 방지
    private var lastExecutedStep = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "접근성 서비스 연결됨")
        showToast("WA 자동전송 서비스 활성화됨 ✅")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != WHATSAPP_PACKAGE) return
        if (!isReadyToSend) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (photoMode) {
                // 이전 예약 취소 후 재스케줄 (중복 방지)
                handler.removeCallbacks(photoRunnable)
                handler.postDelayed(photoRunnable, 900)
            } else {
                handler.removeCallbacks(sendRunnable)
                handler.postDelayed(sendRunnable, 800)
            }
        }
    }

    // ─── 텍스트 전송 (기존 로직 유지) ────────────────────────────────────────

    private fun attemptSend() {
        if (!isReadyToSend || photoMode) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != WHATSAPP_PACKAGE) return

        Log.d(TAG, "전송 버튼 탐색 중...")
        val sendNode = findSendButton(root)
        if (sendNode != null) {
            Log.d(TAG, "전송 버튼 발견: ${sendNode.viewIdResourceName}")
            isReadyToSend = false
            sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ SenderService.instance?.onMessageSent() }, 300)
        } else {
            Log.d(TAG, "전송 버튼 못 찾음, 좌표 탭 시도")
            tapCoordinate(0.92f, 0.88f) {
                isReadyToSend = false
                handler.postDelayed({ SenderService.instance?.onMessageSent() }, 500)
            }
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/mic_or_send",
            "com.whatsapp:id/audio_or_send"
        )
        for (id in sendIds) {
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isClickable }?.let { return it }
        }
        for (desc in listOf("보내기", "Send", "전송")) {
            root.findAccessibilityNodeInfosByText(desc)
                .firstOrNull { it.isClickable }?.let { return it }
        }
        return findBottomRightButton(root)
    }

    // ─── 사진 전송 상태 머신 ─────────────────────────────────────────────────

    private fun attemptPhotoStep() {
        if (!isReadyToSend || !photoMode) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != WHATSAPP_PACKAGE) return

        // 이미 이 스텝을 실행했으면 스킵 (중복 실행 방지)
        if (photoStep == lastExecutedStep) return

        Log.d(TAG, "📷 사진 스텝 실행: $photoStep")

        when (photoStep) {
            STEP_ATTACH  -> clickAttachButton(root)
            STEP_GALLERY -> clickGallery(root)
            STEP_IMAGE   -> selectFirstImage(root)
            STEP_CAPTION -> addCaption(root)
            STEP_SEND    -> clickSendAfterPhoto(root)
        }
    }

    /** STEP_ATTACH: 첨부(클립) 버튼 클릭 */
    private fun clickAttachButton(root: AccessibilityNodeInfo) {
        lastExecutedStep = STEP_ATTACH

        val attachIds = listOf(
            "com.whatsapp:id/attachment_button",
            "com.whatsapp:id/clip",
            "com.whatsapp:id/extra_toolbar_button",
            "com.whatsapp:id/compose_extra_button"
        )
        for (id in attachIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isClickable }
            if (node != null) {
                Log.d(TAG, "첨부 버튼 클릭 (ID): ${node.viewIdResourceName}")
                photoStep = STEP_GALLERY
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        for (desc in listOf("Attach", "첨부", "파일 첨부", "첨부하기")) {
            val node = root.findAccessibilityNodeInfosByText(desc).firstOrNull { it.isClickable }
            if (node != null) {
                Log.d(TAG, "첨부 버튼 클릭 (텍스트): $desc")
                photoStep = STEP_GALLERY
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        // 폴백: 입력창 왼쪽 클립 위치 좌표 탭
        Log.d(TAG, "첨부 버튼 못 찾음 → 좌표 탭")
        photoStep = STEP_GALLERY
        tapCoordinate(0.08f, 0.91f) {}
    }

    /** STEP_GALLERY: 첨부 메뉴에서 갤러리 항목 클릭 */
    private fun clickGallery(root: AccessibilityNodeInfo) {
        lastExecutedStep = STEP_GALLERY

        for (text in listOf("Gallery", "갤러리", "사진 및 동영상", "Photos & Videos")) {
            val node = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isClickable }
            if (node != null) {
                Log.d(TAG, "갤러리 클릭 (텍스트): $text")
                photoStep = STEP_IMAGE
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        for (id in listOf("com.whatsapp:id/pickimagegallery", "com.whatsapp:id/gallery")) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isClickable }
            if (node != null) {
                Log.d(TAG, "갤러리 클릭 (ID)")
                photoStep = STEP_IMAGE
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        Log.d(TAG, "갤러리 못 찾음 → 좌표 탭")
        photoStep = STEP_IMAGE
        tapCoordinate(0.18f, 0.68f) {}
    }

    /** STEP_IMAGE: 갤러리에서 첫 번째 이미지 선택 (MediaStore로 복사한 사진) */
    private fun selectFirstImage(root: AccessibilityNodeInfo) {
        lastExecutedStep = STEP_IMAGE

        val nextStep = if (pendingMessage.isNullOrBlank()) STEP_SEND else STEP_CAPTION

        for (id in listOf(
            "com.whatsapp:id/media_list",
            "com.whatsapp:id/grid",
            "com.whatsapp:id/photos_grid",
            "com.whatsapp:id/recycler_view"
        )) {
            val grid = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            if (grid != null && grid.childCount > 0) {
                val firstItem = grid.getChild(0)
                if (firstItem != null) {
                    Log.d(TAG, "첫 번째 이미지 클릭 (그리드)")
                    photoStep = nextStep
                    firstItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }
        Log.d(TAG, "이미지 그리드 못 찾음 → 좌표 탭")
        photoStep = nextStep
        tapCoordinate(0.15f, 0.28f) {}
    }

    /** STEP_CAPTION: 이미지 캡션 입력 */
    private fun addCaption(root: AccessibilityNodeInfo) {
        lastExecutedStep = STEP_CAPTION
        photoStep = STEP_SEND   // 이 스텝 처리 완료, 다음은 전송

        for (id in listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp:id/entry",
            "com.whatsapp:id/photo_caption"
        )) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled }
            if (node != null) {
                Log.d(TAG, "캡션 입력")
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    pendingMessage ?: ""
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return
            }
        }
        Log.d(TAG, "캡션 필드 못 찾음 → 전송 단계로 진행")
    }

    /** STEP_SEND: 사진 전송 버튼 클릭 */
    private fun clickSendAfterPhoto(root: AccessibilityNodeInfo) {
        lastExecutedStep = STEP_SEND

        val sendNode = findSendButton(root)
        if (sendNode != null) {
            Log.d(TAG, "사진 전송 버튼 클릭")
            finishPhotoSend()
            sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            Log.d(TAG, "전송 버튼 못 찾음 → 좌표 탭")
            tapCoordinate(0.92f, 0.88f) { finishPhotoSend() }
        }
    }

    private fun finishPhotoSend() {
        isReadyToSend = false
        photoMode = false
        photoStep = STEP_ATTACH
        lastExecutedStep = -1
        handler.postDelayed({ SenderService.instance?.onMessageSent() }, 500)
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    private fun tapCoordinate(xFraction: Float, yFraction: Float, onDone: () -> Unit) {
        val display = resources.displayMetrics
        val x = display.widthPixels * xFraction
        val y = display.heightPixels * yFraction
        Log.d(TAG, "좌표 탭: ($x, $y)")

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { onDone() }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.d(TAG, "제스처 취소됨")
            }
        }, handler)
    }

    private fun findBottomRightButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val display = resources.displayMetrics
        val sw = display.widthPixels
        val sh = display.heightPixels
        return findNodeInRegion(node, (sw * 0.6).toInt(), (sh * 0.6).toInt(), sw, sh)
    }

    private fun findNodeInRegion(
        node: AccessibilityNodeInfo,
        left: Int, top: Int, right: Int, bottom: Int
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isClickable && node.childCount == 0) {
            if (bounds.left >= left && bounds.top >= top &&
                bounds.right <= right && bounds.bottom <= bottom
            ) {
                val cls = node.className?.toString() ?: ""
                if (cls.contains("Button") || cls.contains("Image")) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeInRegion(child, left, top, right, bottom)
            if (result != null) return result
        }
        return null
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}
}
