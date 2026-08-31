package com.waauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume

class SenderService : Service() {

    companion object {
        private const val TAG = "SenderService"
        private const val CHANNEL_ID = "wa_sender_channel"
        private const val NOTIF_ID = 1001
        const val EXTRA_GROUP_ID  = "group_id"
        const val EXTRA_PHOTO_PATH = "photo_path"   // 사진 캐시 경로 (없으면 텍스트 전송)
        var instance: SenderService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var messageSentContinuation: CancellableContinuation<Unit>? = null

    // 현재 전송 세션에서 삽입한 MediaStore URI (전송 후 삭제)
    private var mediaStoreUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val groupId   = intent?.getStringExtra(EXTRA_GROUP_ID)
        val photoPath = intent?.getStringExtra(EXTRA_PHOTO_PATH)
        startForeground(NOTIF_ID, buildNotification("WhatsApp 전송 준비 중..."))
        scope.launch { startSending(groupId, photoPath) }
        return START_NOT_STICKY
    }

    private suspend fun startSending(groupId: String?, photoPath: String?) {
        // 오버레이 권한 체크
        if (!Settings.canDrawOverlays(this)) {
            showToast("❌ '다른 앱 위에 표시' 권한이 없습니다!")
            stopSelf(); return
        }

        // 그룹 로드
        val group = if (groupId != null) DataManager.getGroup(this, groupId) else null
        if (group == null) {
            showToast("❌ 그룹을 찾을 수 없습니다")
            stopSelf(); return
        }

        val contacts = group.contacts
        val message  = group.message

        if (contacts.isEmpty()) {
            showToast("❌ [${group.name}] 연락처가 없습니다!")
            stopSelf(); return
        }

        val isPhotoMode = !photoPath.isNullOrBlank()

        // 텍스트 전용 모드에서는 메시지 필수
        if (!isPhotoMode && message.isBlank()) {
            showToast("❌ [${group.name}] 메시지가 없습니다!\n편집에서 메시지를 입력해주세요")
            stopSelf(); return
        }

        if (WAAccessibilityService.instance == null) {
            showToast("❌ 접근성 서비스가 꺼져 있습니다!")
            stopSelf(); return
        }

        // 사진 모드: MediaStore에 사진 복사 (WhatsApp 갤러리 최상단에 나타남)
        if (isPhotoMode) {
            val uri = withContext(Dispatchers.IO) {
                copyPhotoToMediaStore(photoPath!!)
            }
            if (uri == null) {
                showToast("❌ 사진을 처리할 수 없습니다")
                stopSelf(); return
            }
            mediaStoreUri = uri
            showToast("📷 [${group.name}] 사진 전송 시작! ${contacts.size}명에게 보냅니다")
        } else {
            showToast("📤 [${group.name}] 전송 시작! ${contacts.size}명에게 보냅니다")
        }

        val encodedMessage = if (message.isNotBlank())
            URLEncoder.encode(message, "UTF-8") else ""

        var sentCount = 0

        for (contact in contacts) {
            updateNotification("[${group.name}] 전송 중... $sentCount/${contacts.size} (${contact.name})")
            Log.d(TAG, "전송 시도: ${contact.name} / ${contact.phone}")

            // Accessibility Service 상태 설정
            WAAccessibilityService.pendingMessage = if (message.isNotBlank()) message else null
            WAAccessibilityService.isReadyToSend  = true
            WAAccessibilityService.photoMode      = isPhotoMode
            WAAccessibilityService.photoStep      = WAAccessibilityService.STEP_ATTACH

            // WhatsApp 채팅 열기
            // 사진 모드: 텍스트 없이 열어야 입력창이 비어 있어 캡션 입력 가능
            val uri: Uri = if (isPhotoMode) {
                Uri.parse("whatsapp://send?phone=${contact.phone}")
            } else {
                Uri.parse("whatsapp://send?phone=${contact.phone}&text=${encodedMessage}")
            }

            val openIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                startActivity(openIntent)

                // 사진 전송은 단계가 많아 30초로 타임아웃 설정
                val timeout = if (isPhotoMode) 30_000L else 15_000L

                withTimeout(timeout) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        messageSentContinuation = cont
                    }
                }
                sentCount++
                Log.d(TAG, "전송 완료: ${contact.name} ($sentCount/${contacts.size})")

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "타임아웃: ${contact.name}")
                showToast("⚠️ ${contact.name} 타임아웃 - 다음으로 넘어갑니다")
                WAAccessibilityService.isReadyToSend = false
                WAAccessibilityService.photoMode     = false
            } catch (e: Exception) {
                Log.e(TAG, "전송 실패: ${e.message}")
                WAAccessibilityService.isReadyToSend = false
                WAAccessibilityService.photoMode     = false
            }

            delay(DataManager.getDelayBetweenMessages())
        }

        // MediaStore에 임시 삽입한 사진 삭제 (갤러리에 남지 않도록)
        if (isPhotoMode) {
            withContext(Dispatchers.IO) { cleanupMediaStore() }
        }

        updateNotification("전송 완료! $sentCount/${contacts.size}명")
        showToast("✅ [${group.name}] 완료! $sentCount/${contacts.size}명에게 보냈습니다")
        delay(3000L)
        stopSelf()
    }

    /**
     * 선택한 사진을 MediaStore Pictures/WAAuto 에 복사합니다.
     * → WhatsApp 갤러리 피커의 최신 사진 목록 맨 위에 나타납니다.
     * API 29(Android 10) 이상: 권한 없이 RELATIVE_PATH 사용
     * API 26-28: 공용 Pictures 폴더에 직접 저장 후 MediaStore 등록
     */
    private fun copyPhotoToMediaStore(cachePath: String): Uri? {
        return try {
            val srcFile = File(cachePath)
            if (!srcFile.exists()) return null

            val fileName = "wa_send_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: Scoped Storage – 권한 없이 MediaStore에 쓸 수 있음
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WAAuto")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    srcFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Log.d(TAG, "MediaStore 복사 완료 (API 29+): $uri")
                uri
            } else {
                // API 26-28: 공용 Pictures/WAAuto 에 직접 저장
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val waDir = File(picturesDir, "WAAuto").apply { mkdirs() }
                val destFile = File(waDir, fileName)
                srcFile.copyTo(destFile, overwrite = true)

                @Suppress("DEPRECATION")
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                Log.d(TAG, "MediaStore 복사 완료 (API<29): $uri")
                uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 복사 실패: ${e.message}")
            null
        }
    }

    /** 전송 완료 후 MediaStore에 삽입한 임시 사진 삭제 */
    private fun cleanupMediaStore() {
        try {
            val uri = mediaStoreUri ?: return
            contentResolver.delete(uri, null, null)
            mediaStoreUri = null
            Log.d(TAG, "MediaStore 임시 사진 삭제 완료")
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 삭제 실패: ${e.message}")
        }
    }

    fun onMessageSent() {
        Log.d(TAG, "메시지 전송 확인됨 - 다음으로")
        messageSentContinuation?.resume(Unit)
        messageSentContinuation = null
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("📱 WA 자동 전송")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "WhatsApp 자동 전송", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
