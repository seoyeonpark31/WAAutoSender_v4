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
 * 메이크업 (빠른전송).
 * 저장 없이 붙여넣은 번호로 즉시 전송.
 * 사진 첨부 선택 가능.
 */
class MakeupActivity : AppCompatActivity() {

    private lateinit var etLinks: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnExtract: Button
    private lateinit var btnShowNumbers: Button
    private lateinit var btnSend: Button
    private lateinit var layoutExtractResult: LinearLayout
    private lateinit var tvExtractResult: TextView

    // 사진 관련 뷰
    private lateinit var layoutNoPhoto: LinearLayout
    private lateinit var layoutPhotoSelected: LinearLayout
    private lateinit var ivPhotoThumb: ImageView
    private lateinit var tvPhotoName: TextView
    private lateinit var btnPickPhoto: Button
    private lateinit var btnChangePhoto: Button
    private lateinit var btnRemovePhoto: Button

    private var extractedPhones: List<String> = emptyList()
    private var selectedPhotoCachePath: String? = null

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handlePhotoSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_makeup)

        supportActionBar?.title = "메이크업 (빠른전송)"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etLinks             = findViewById(R.id.etLinks)
        etMessage           = findViewById(R.id.etMessage)
        btnExtract          = findViewById(R.id.btnExtract)
        btnShowNumbers      = findViewById(R.id.btnShowNumbers)
        btnSend             = findViewById(R.id.btnSend)
        layoutExtractResult = findViewById(R.id.layoutExtractResult)
        tvExtractResult     = findViewById(R.id.tvExtractResult)

        layoutNoPhoto       = findViewById(R.id.layoutNoPhoto)
        layoutPhotoSelected = findViewById(R.id.layoutPhotoSelected)
        ivPhotoThumb        = findViewById(R.id.ivPhotoThumb)
        tvPhotoName         = findViewById(R.id.tvPhotoName)
        btnPickPhoto        = findViewById(R.id.btnPickPhoto)
        btnChangePhoto      = findViewById(R.id.btnChangePhoto)
        btnRemovePhoto      = findViewById(R.id.btnRemovePhoto)

        btnExtract.setOnClickListener    { extractNumbers(showToastIfEmpty = true) }
        btnShowNumbers.setOnClickListener { showExtractedNumbers() }
        btnSend.setOnClickListener       { startSend() }

        btnPickPhoto.setOnClickListener   { launchPicker() }
        btnChangePhoto.setOnClickListener { launchPicker() }
        btnRemovePhoto.setOnClickListener { removePhoto() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─── 사진 선택 ──────────────────────────────────────────────────────────

    private fun launchPicker() {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun handlePhotoSelected(uri: Uri) {
        val path = copyToCache(uri)
        if (path == null) {
            Toast.makeText(this, "사진을 불러올 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPhotoCachePath = path
        ivPhotoThumb.setImageURI(uri)
        tvPhotoName.text = getDisplayName(contentResolver, uri) ?: "사진"
        layoutNoPhoto.visibility       = View.GONE
        layoutPhotoSelected.visibility = View.VISIBLE
    }

    private fun removePhoto() {
        selectedPhotoCachePath = null
        ivPhotoThumb.setImageDrawable(null)
        layoutNoPhoto.visibility       = View.VISIBLE
        layoutPhotoSelected.visibility = View.GONE
    }

    private fun copyToCache(uri: Uri): String? {
        return try {
            val dest = java.io.File(cacheDir, "send_photo.jpg")
            contentResolver.openInputStream(uri)?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.absolutePath
        } catch (e: Exception) { null }
    }

    private fun getDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
            }
        } catch (e: Exception) { null }
    }

    // ─── 번호 추출 ──────────────────────────────────────────────────────────

    private fun extractNumbers(showToastIfEmpty: Boolean): Boolean {
        val text = etLinks.text.toString()
        if (text.isBlank()) {
            if (showToastIfEmpty)
                Toast.makeText(this, "먼저 번호를 붙여넣어주세요", Toast.LENGTH_SHORT).show()
            layoutExtractResult.visibility = View.GONE
            extractedPhones = emptyList()
            return false
        }

        extractedPhones = DataManager.parsePhones(text)

        if (extractedPhones.isEmpty()) {
            layoutExtractResult.visibility = View.GONE
            if (showToastIfEmpty)
                Toast.makeText(this, "번호를 찾지 못했어요\n국가코드 포함 7자리 이상 숫자가 필요합니다", Toast.LENGTH_LONG).show()
            return false
        }

        tvExtractResult.text = "📞 ${extractedPhones.size}개 번호 인식됨"
        layoutExtractResult.visibility = View.VISIBLE
        return true
    }

    private fun showExtractedNumbers() {
        if (extractedPhones.isEmpty()) return
        val list = extractedPhones.joinToString("\n") { "  +$it" }
        AlertDialog.Builder(this)
            .setTitle("인식된 번호 (${extractedPhones.size}개)")
            .setMessage(list)
            .setPositiveButton("확인", null)
            .show()
    }

    // ─── 전송 ───────────────────────────────────────────────────────────────

    private fun startSend() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            return
        }
        if (extractedPhones.isEmpty()) {
            if (!extractNumbers(showToastIfEmpty = true)) return
        }

        val message  = etMessage.text.toString().trim()
        val hasPhoto = selectedPhotoCachePath != null

        if (message.isBlank() && !hasPhoto) {
            Toast.makeText(this, "메시지를 입력하거나 사진을 선택해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val tempContacts = extractedPhones.mapIndexed { i, phone ->
            Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = phone)
        }
        val tempGroup = Group(
            id       = DataManager.ID_MAKEUP,
            name     = DataManager.NAME_MAKEUP,
            contacts = tempContacts,
            message  = message
        )
        DataManager.saveGroup(this, tempGroup)

        val intent = Intent(this, SenderService::class.java)
            .putExtra(SenderService.EXTRA_GROUP_ID, DataManager.ID_MAKEUP)

        if (hasPhoto) {
            intent.putExtra(SenderService.EXTRA_PHOTO_PATH, selectedPhotoCachePath)
        }

        startForegroundService(intent)

        val label = if (hasPhoto) "📷 사진+메시지" else "📤 텍스트"
        Toast.makeText(this, "$label ${tempContacts.size}명에게 전송 시작!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
