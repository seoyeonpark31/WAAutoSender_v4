package com.waauto

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button

    private lateinit var tvAclassCount: TextView
    private lateinit var tvBclassCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay      = findViewById(R.id.btnOverlay)
        tvAclassCount   = findViewById(R.id.tvAclassCount)
        tvBclassCount   = findViewById(R.id.tvBclassCount)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "'와츠앱 자동전송' 을 찾아 켜주세요", Toast.LENGTH_LONG).show()
        }

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<View>(R.id.cardAclass).setOnClickListener {
            openClass("aclass", "A반")
        }
        findViewById<View>(R.id.cardBclass).setOnClickListener {
            openClass("bclass", "B반")
        }
        findViewById<View>(R.id.cardMakeup).setOnClickListener {
            startActivity(Intent(this, MakeupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
        updateCounts()
    }

    private fun openClass(classId: String, className: String) {
        startActivity(
            Intent(this, ClassActivity::class.java)
                .putExtra(ClassActivity.EXTRA_CLASS_ID, classId)
                .putExtra(ClassActivity.EXTRA_CLASS_NAME, className)
        )
    }

    private fun updateCounts() {
        val a = DataManager.getGroup(this, "aclass")?.contacts?.size ?: 0
        val b = DataManager.getGroup(this, "bclass")?.contacts?.size ?: 0
        tvAclassCount.text = "${a}명 저장됨"
        tvBclassCount.text = "${b}명 저장됨"
    }

    private fun updatePermissionButtons() {
        val accessOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        btnAccessibility.text = if (accessOk) "✅ 접근성 권한 허용됨" else "⚠️ 접근성 권한 허용하기 (필수)"
        btnAccessibility.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (accessOk) Color.parseColor("#388E3C") else Color.parseColor("#FF5722")
        )
        btnOverlay.text = if (overlayOk) "✅ 다른 앱 위에 표시 허용됨" else "⚠️ 다른 앱 위에 표시 허용하기"
        btnOverlay.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (overlayOk) Color.parseColor("#388E3C") else Color.parseColor("#E91E63")
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${WAAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            if (enabled != 1) return false
            val services = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(services)
            splitter.any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }
}
