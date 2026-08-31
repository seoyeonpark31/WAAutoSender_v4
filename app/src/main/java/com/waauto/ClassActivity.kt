package com.waauto

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * A반 / B반 공통 화면.
 *  - 와츠앱 번호를 통째로 붙여넣어 저장
 *  - 메시지 입력
 *  - 사진 첨부 (선택사항)
 *  - 저장된 번호 전체에 전송
 */
class ClassActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLASS_ID   = "class_id"
        const val EXTRA_CLASS_NAME = "class_name"
    }

    private lateinit var classId: String
    private lateinit var className: String

    private lateinit var tvSavedCount: TextView
    private lateinit var tvSavedPreview: TextView
    private lateinit var etPaste: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnSaveOverwrite: Button
    private lateinit var btnSaveAppend: Button
    private lateinit var btnClear: Button
    private lateinit var btnSend: Button

    // 사진 관련 뷰
    private lateinit var layoutNoPhoto: LinearLayout
    private lateinit var layoutPhotoSelected: LinearLayout
    private lateinit var ivPhotoThumb: ImageView
    private lateinit var tvPhotoName: TextView
    private lateinit var btnPickPhoto: Button
    private lateinit var btnChangePhoto: Button
    private lateinit var btnRemovePhoto: Button

    // 선택된 사진의 캐시 경로 (null이면 사진 없음 → 텍스트 전송)
    private var selectedPhotoCachePath: String? = null

    // 사진 피커 (별도 권한 불필요)
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handlePhotoSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_class)

        classId   = intent.getStringExtra(EXTRA_CLASS_ID)   ?: run { finish(); return }
        className = intent.getStringExtra(EXTRA_CLASS_NAME) ?: classId

        supportActionBar?.title = className
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvSavedCount     = findViewById(R.id.tvSavedCount)
        tvSavedPreview   = findViewById(R.id.tvSavedPreview)
        etPaste          = findViewById(R.id.etPaste)
        etMessage        = findViewById(R.id.etMessage)
        btnSaveOverwrite = findViewById(R.id.btnSaveOverwrite)
        btnSaveAppend    = findViewById(R.id.btnSaveAppend)
        btnClear         = findViewById(R.id.btnClear)
        btnSend          = findViewById(R.id.btnSend)

        layoutNoPhoto      = findViewById(R.id.layoutNoPhoto)
        layoutPhotoSelected = findViewById(R.id.layoutPhotoSelected)
        ivPhotoThumb       = findViewById(R.id.ivPhotoThumb)
        tvPhotoName        = findViewById(R.id.tvPhotoName)
        btnPickPhoto       = findViewById(R.id.btnPickPhoto)
        btnChangePhoto     = findViewById(R.id.btnChangePhoto)
        btnRemovePhoto     = findViewById(R.id.btnRemovePhoto)

        val group = DataManager.getGroup(this, classId)
        etMessage.setText(group?.message ?: "")
        refreshSaved()

        btnSaveOverwrite.setOnClickListener { save(overwrite = true) }
        btnSaveAppend.setOnClickListener    { save(overwrite = false) }
        btnClear.setOnClickListener         { confirmClear() }
        btnSend.setOnClickListener          { send() }

        btnPickPhoto.setOnClickListener    { launchPicker() }
        btnChangePhoto.setOnClickListener  { launchPicker() }
        btnRemovePhoto.setOnClickListener  { removePhoto() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─── 사진 선택 ──────────────────────────────────────────────────────────

    private fun launchPicker() {
        pickPhoto.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun handlePhotoSelected(uri: Uri) {
        val path = copyToCache(uri)
        if (path == null) {
            Toast.makeText(this, "사진을 불러올 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPhotoCachePath = path

        // 썸네일
        ivPhotoThumb.setImageURI(uri)

        // 파일명
        val name = getDisplayName(contentResolver, uri) ?: "사진"
        tvPhotoName.text = name

        // UI 전환
        layoutNoPhoto.visibility      = View.GONE
        layoutPhotoSelected.visibility = View.VISIBLE
    }

    private fun removePhoto() {
        selectedPhotoCachePath = null
        ivPhotoThumb.setImageDrawable(null)
        layoutNoPhoto.visibility       = View.VISIBLE
        layoutPhotoSelected.visibility = View.GONE
    }

    /** 사진을 앱 캐시 디렉터리에 복사하고 경로 반환 */
    private fun copyToCache(uri: Uri): String? {
        return try {
            val dest = java.io.File(cacheDir, "send_photo.jpg")
            contentResolver.openInputStream(uri)?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun getDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
            }
        } catch (e: Exception) { null }
    }

    // ─── 저장된 연락처 ──────────────────────────────────────────────────────

    private fun refreshSaved() {
        val contacts = DataManager.getGroup(this, classId)?.contacts ?: emptyList()
        tvSavedCount.text = "💾 저장된 연락처: ${contacts.size}명"
        tvSavedPreview.text = when {
            contacts.isEmpty() -> "아직 저장된 번호가 없어요.\n아래에 번호를 붙여넣고 저장하세요."
            else -> {
                val preview = contacts.take(8).joinToString("\n") { "  ${it.name}: +${it.phone}" }
                if (contacts.size > 8) "$preview\n  ... 외 ${contacts.size - 8}명" else preview
            }
        }
    }

    private fun save(overwrite: Boolean) {
        val phones = DataManager.parsePhones(etPaste.text.toString())
        if (phones.isEmpty()) {
            Toast.makeText(this, "붙여넣은 텍스트에서 번호를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }

        val existing = DataManager.getGroup(this, classId)?.contacts ?: emptyList()

        val merged: List<Contact> = if (overwrite) {
            phones.mapIndexed { i, p ->
                Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = p)
            }
        } else {
            val existingPhones = existing.map { it.phone }.toSet()
            val toAdd = phones.filter { it !in existingPhones }
            val combined = existing.map { it.phone } + toAdd
            combined.mapIndexed { i, p ->
                Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = p)
            }
        }

        DataManager.saveGroup(
            this,
            Group(
                id = classId,
                name = className,
                contacts = merged,
                message = etMessage.text.toString().trim()
            )
        )

        etPaste.setText("")
        refreshSaved()
        val added = merged.size - if (overwrite) 0 else existing.size
        val msg = if (overwrite) "✅ ${merged.size}명 저장됨 (덮어쓰기)"
                  else "✅ ${added}명 추가됨 (총 ${merged.size}명)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("${className}의 저장된 모든 번호를 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                DataManager.saveGroup(
                    this,
                    Group(
                        id = classId,
                        name = className,
                        contacts = emptyList(),
                        message = etMessage.text.toString().trim()
                    )
                )
                refreshSaved()
                Toast.makeText(this, "🗑️ 비웠어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ─── 전송 ───────────────────────────────────────────────────────────────

    private fun send() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            return
        }

        val msg = etMessage.text.toString().trim()
        val hasPhoto = selectedPhotoCachePath != null

        // 텍스트도 없고 사진도 없으면 전송 불가
        if (msg.isBlank() && !hasPhoto) {
            Toast.makeText(this, "메시지를 입력하거나 사진을 선택해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val current = DataManager.getGroup(this, classId)
            ?: Group(id = classId, name = className)
        val updated = current.copy(message = msg)
        DataManager.saveGroup(this, updated)

        if (updated.contacts.isEmpty()) {
            Toast.makeText(this, "저장된 번호가 없어요. 먼저 번호를 붙여넣고 저장하세요", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, SenderService::class.java)
            .putExtra(SenderService.EXTRA_GROUP_ID, classId)

        // 사진이 선택된 경우 캐시 경로 전달
        if (hasPhoto) {
            intent.putExtra(SenderService.EXTRA_PHOTO_PATH, selectedPhotoCachePath)
        }

        startForegroundService(intent)

        val label = if (hasPhoto) "📷 사진+메시지" else "📤 텍스트"
        Toast.makeText(this, "$label ${updated.contacts.size}명에게 전송 시작!", Toast.LENGTH_SHORT).show()
    }
}
