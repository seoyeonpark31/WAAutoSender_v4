package com.waauto

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Contact(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val phone: String   // 국가코드 포함 숫자만: 821012345678
)

data class Group(
    val id: String,
    val name: String,
    val contacts: List<Contact> = emptyList(),
    val message: String = ""
)

object DataManager {
    private const val PREF_NAME  = "waauto_prefs"
    private const val KEY_GROUPS = "groups_v3"
    private val gson = Gson()

    // 고정 클래스 정의 (A반 / B반 만 저장 대상)
    val CLASSES = listOf(
        Triple("aclass", "A반", "#2196F3"),
        Triple("bclass", "B반", "#4CAF50")
    )
    const val ID_MAKEUP = "makeup"
    const val NAME_MAKEUP = "메이크업"

    fun getGroups(context: Context): List<Group> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_GROUPS, null) ?: return initDefaults(context)
        return try {
            val type = object : TypeToken<List<Group>>() {}.type
            gson.fromJson<List<Group>>(json, type) ?: initDefaults(context)
        } catch (e: Exception) {
            initDefaults(context)
        }
    }

    fun getGroup(context: Context, groupId: String): Group? =
        getGroups(context).find { it.id == groupId }

    fun saveGroup(context: Context, group: Group) {
        val groups = getGroups(context).toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        saveAll(context, groups)
    }

    private fun saveAll(context: Context, groups: List<Group>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROUPS, gson.toJson(groups)).apply()
    }

    private fun initDefaults(context: Context): List<Group> {
        val defaults = CLASSES.map { (id, name, _) -> Group(id = id, name = name) }
        saveAll(context, defaults)
        return defaults
    }

    fun getDelayBetweenMessages(): Long = 4000L

    /**
     * 사용자가 와츠앱에서 드래그·복사해 붙여넣은 텍스트에서 전화번호만 뽑아낸다.
     * 지원 형식:
     *  - wa.me 링크 (https://wa.me/233508935316, http://wa.me//+233...)
     *  - 그냥 번호 (+233 50 893 5316, 233-508-935-316, 233 508 935 316)
     *  - 줄/쉼표/세미콜론/탭으로 구분된 여러 번호
     *
     * 반환되는 번호는 국가코드 포함 숫자만 (7~15자리), 중복 제거됨.
     */
    fun parsePhones(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        // 1) wa.me 링크가 섞여 있으면 그것부터 추출
        val waMe = Regex("""wa\.me/+\+?(\d{7,15})""")
            .findAll(rawText)
            .map { it.groupValues[1] }
            .toList()

        // 2) 일반 텍스트: 줄/쉼표/세미콜론으로 토큰화 후 각각에서 숫자만 남김
        val raw = rawText
            .split(Regex("[\\r\\n,;\\t]+"))
            .map { token ->
                // wa.me 링크 부분은 제거 (이미 위에서 처리)
                token.replace(Regex("""wa\.me/+\+?\d{7,15}"""), "")
                     .replace(Regex("[^0-9]"), "")
            }
            .filter { it.length in 7..15 }

        return (waMe + raw)
            .map { it.trimStart('0') }      // 앞의 0 제거 (국제번호로 통일 위해)
            .filter { it.length in 7..15 }
            .distinct()
    }
}
