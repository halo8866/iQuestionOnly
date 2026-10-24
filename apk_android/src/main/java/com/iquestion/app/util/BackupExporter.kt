package com.iquestion.app.util

import com.google.gson.GsonBuilder
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.model.JsonQuestion
import com.iquestion.app.model.JsonQuestionBank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份导出器：把所有科目/章节/题目导出为可重新导入的 JSON 文件
 *
 * 导出格式特点：
 * - 每道题都带题目级 subject/chapter 字段，导入时按其自动创建/匹配科目与章节，完整还原结构
 */
object BackupExporter {

    /**
     * 生成备份 JSON 字符串（格式化、中文不转义）
     */
    suspend fun buildJson(dao: QuestionDao): String = withContext(Dispatchers.IO) {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        val subjects = dao.getAllSubjectsWithCounts()
        val questions = mutableListOf<JsonQuestion>()
        var chapterCount = 0

        for (subject in subjects) {
            val chapters = dao.getChaptersBySubject(subject.id)
            chapterCount += chapters.size
            for (chapter in chapters) {
                for (q in dao.getQuestionsByChapter(chapter.id)) {
                    questions.add(
                        JsonQuestion(
                            type = q.type,
                            question = q.question,
                            options = JsonImporter.parseOptions(q.options).ifEmpty { null },
                            answer = q.answer,
                            analysis = q.analysis,
                            subject = subject.name,
                            chapter = chapter.name,
                            isValid = if (q.isValid) null else false // 有效题不导出字段，无效题导出 false
                        )
                    )
                }
            }
        }

        val bank = JsonQuestionBank(
            name = "题梦 备份题库",
            description = "包含 ${subjects.size} 个科目、$chapterCount 个章节、${questions.size} 道题；" +
                    "导入本文件时会按题目级科目/章节自动还原结构，可用于重装软件后恢复",
            questions = questions
        )

        gson.toJson(bank)
    }
}
