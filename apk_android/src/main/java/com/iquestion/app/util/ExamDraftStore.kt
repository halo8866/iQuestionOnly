package com.iquestion.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 考试草稿（暂存考试）：将一次未完成的科目考试状态持久化，下次进入同科目考试时可恢复。
 * 只保留一份草稿（按 subjectId 区分）。使用 Gson 序列化到 SharedPreferences。
 */
data class ExamDraft(
    val subjectId: Long,
    val subjectName: String,
    val chapterIds: List<Long>,
    val countSingle: Int,
    val countMultiple: Int,
    val countJudge: Int,
    val countEssay: Int,
    val durationMinutes: Int,
    val remainingMillis: Long,
    val questionIds: List<Long>,
    val currentIndex: Int,
    val userSelections: Map<Int, List<String>>,
    val savedAt: Long
)

object ExamDraftStore {

    private const val PREFS = "exam_draft"
    private const val KEY_DRAFT = "draft"

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存一份草稿（覆盖旧草稿） */
    fun save(context: Context, draft: ExamDraft) {
        prefs(context).edit().putString(KEY_DRAFT, gson.toJson(draft)).apply()
    }

    /** 读取草稿 */
    fun load(context: Context): ExamDraft? {
        val json = prefs(context).getString(KEY_DRAFT, null) ?: return null
        return try {
            gson.fromJson(json, ExamDraft::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** 清除草稿 */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_DRAFT).apply()
    }
}
