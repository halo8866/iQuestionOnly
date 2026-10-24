package com.iquestion.app.util

import com.google.gson.Gson
import com.iquestion.app.data.Question
import com.iquestion.app.data.QuestionBank
import com.iquestion.app.model.JsonQuestion
import com.iquestion.app.model.JsonQuestionBank
import org.json.JSONArray

/**
 * JSON 题库导入器
 *
 * 支持的 JSON 格式：
 * {
 *   "name": "题库名称",
 *   "description": "描述（可选）",
 *   "subject": "科目名称（顶层或题目级至少指定其一）",
 *   "chapter": "默认章节名称（顶层或题目级至少指定其一）",
 *   "questions": [
 *     {
 *       "type": "single | multiple | judge | essay",
 *       "question": "题目内容",
 *       "options": ["A. 选项1", "B. 选项2", ...],   // 判断题/解答题可省略
 *       "answer": "A" 或 ["A","B"] 或 "正确"/"错误" 或 "解答题参考答案文本",
 *       "analysis": "解析（可选）",
 *       "chapter": "章节名称（可选，覆盖顶层默认章节，用于同一文件导入多个章节）",
 *       "subject": "科目名称（可选，题目级覆盖，备份导出文件使用）"
 *     }
 *   ]
 * }
 *
 * 注意：导入时科目或章节不存在会自动创建（通过导入即可添加新科目/新章节）。
 */
object JsonImporter {

    private val gson = Gson()

    /**
     * 导入解析结果
     * @param bank 题库批次记录
     * @param questions 题目列表（chapterId 未设置，chapterName/subjectName 携带每题目标科目/章节名）
     * @param subjectName JSON 顶层科目名（可选，题目级 subject 可覆盖）
     * @param defaultChapterName 顶层默认章节名（题目级 chapter 可覆盖）
     */
    data class ImportData(
        val bank: QuestionBank,
        val questions: List<Question>,
        val subjectName: String?,
        val defaultChapterName: String?
    )

    /**
     * 解析 JSON 字符串
     * @throws IllegalArgumentException 解析失败时抛出
     */
    fun parse(json: String): ImportData {
        val bank: JsonQuestionBank? = try {
            gson.fromJson(json, JsonQuestionBank::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 格式错误：${e.message}")
        }

        if (bank == null) {
            throw IllegalArgumentException("无法解析 JSON 文件")
        }

        val bankName = bank.name?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("题库名称(name)不能为空")

        val subjectName = bank.subject?.trim()?.takeIf { it.isNotEmpty() }
        val defaultChapterName = bank.chapter?.trim()?.takeIf { it.isNotEmpty() }

        if (bank.questions.isEmpty()) {
            throw IllegalArgumentException("题库中没有题目")
        }

        val questionBank = QuestionBank(
            name = bankName,
            description = bank.description,
            questionCount = bank.questions.size
        )

        val questions = bank.questions.mapIndexed { index, jsonQ ->
            convertToQuestion(jsonQ, index)
        }

        return ImportData(
            bank = questionBank,
            questions = questions,
            subjectName = subjectName,
            defaultChapterName = defaultChapterName
        )
    }

    private fun convertToQuestion(jsonQ: JsonQuestion, index: Int): Question {
        val type = normalizeType(jsonQ.type)
        val questionText = jsonQ.question?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("第 ${index + 1} 题的题目内容为空")

        val options = jsonQ.options ?: emptyList()
        val answer = normalizeAnswer(jsonQ.answer, type)

        // 判断题如果没有提供选项，自动生成；解答题不需要选项
        val finalOptions = when (type) {
            "judge" -> if (options.isEmpty()) listOf("正确", "错误") else options
            "essay" -> emptyList()
            else -> options
        }

        if (type != "judge" && type != "essay" && finalOptions.isEmpty()) {
            throw IllegalArgumentException("第 ${index + 1} 题缺少选项")
        }

        return Question(
            bankId = 0, // 插入后由调用方设置
            type = type,
            question = questionText,
            options = JSONArray(finalOptions).toString(),
            answer = answer,
            analysis = jsonQ.analysis,
            isValid = jsonQ.isValid ?: true
        ).apply {
            // 题目级科目/章节（可选），不落库，仅用于导入时校验匹配
            subjectName = jsonQ.subject?.trim()?.takeIf { it.isNotEmpty() }
            chapterName = jsonQ.chapter?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 标准化题型
     */
    private fun normalizeType(type: String?): String {
        return when (type?.trim()?.lowercase()) {
            "single", "单选题", "单选", "choice" -> "single"
            "multiple", "多选题", "多选", "multi" -> "multiple"
            "judge", "判断题", "判断", "truefalse", "tf", "boolean" -> "judge"
            "essay", "解答题", "简答题", "简答", "问答", "问答题",
            "shortanswer", "short_answer", "short-answer", "text", "qa", "q&a", "subjective" -> "essay"
            null -> throw IllegalArgumentException("题型(type)不能为空")
            else -> throw IllegalArgumentException("不支持的题型：$type")
        }
    }

    /**
     * 标准化答案
     * 单选：返回单个字母如 "A"
     * 多选：返回逗号分隔如 "A,B"
     * 判断：返回 "正确" 或 "错误"
     * 解答：返回参考答案文本
     */
    private fun normalizeAnswer(answer: Any?, type: String): String {
        if (answer == null) {
            throw IllegalArgumentException("答案不能为空")
        }

        return when (type) {
            "single" -> {
                val s = answer.toString().trim().uppercase()
                if (s.isEmpty()) throw IllegalArgumentException("单选题答案为空")
                s
            }
            "multiple" -> {
                val list = when (answer) {
                    is List<*> -> answer.map { it.toString().trim().uppercase() }
                    is String -> answer.split(",", "，", " ").map { it.trim().uppercase() }
                    else -> throw IllegalArgumentException("多选题答案格式错误")
                }.filter { it.isNotEmpty() }
                if (list.isEmpty()) throw IllegalArgumentException("多选题答案为空")
                list.joinToString(",")
            }
            "judge" -> {
                val s = answer.toString().trim()
                when (s.lowercase()) {
                    "正确", "对", "true", "t", "yes", "y", "是", "a" -> "正确"
                    "错误", "错", "false", "f", "no", "n", "否", "b" -> "错误"
                    else -> s
                }
            }
            "essay" -> {
                // 解答题：answer 为参考答案文本；若误传数组则拼接
                val s = when (answer) {
                    is List<*> -> answer.joinToString("\n") { it.toString() }
                    else -> answer.toString()
                }.trim()
                if (s.isEmpty()) throw IllegalArgumentException("解答题参考答案为空")
                s
            }
            else -> throw IllegalArgumentException("未知题型")
        }
    }

    /**
     * 判断答案是否正确（仅适用于客观题：单选/多选/判断）
     * 解答题为主观题，需用户对照参考答案自评，不参与自动判分
     * @param userAnswer 用户选择的选项列表（选项文本，如 "A. 选项1"）
     * @param question 题目
     * @return 是否正确
     */
    fun isAnswerCorrect(userAnswer: List<String>, question: Question): Boolean {
        if (question.type == "essay") return false
        if (userAnswer.isEmpty()) return false

        val correctOptions = getCorrectOptions(question)
        if (correctOptions.isEmpty()) return false

        // 比较用户选择的选项文本集合与正确选项文本集合
        val userSet = userAnswer.toSet()
        val correctSet = correctOptions.toSet()
        return userSet == correctSet
    }

    /**
     * 获取正确选项的文本列表
     * 解答题返回参考答案文本（单元素列表）
     */
    fun getCorrectOptions(question: Question): List<String> {
        val options = parseOptions(question.options)
        val answer = question.answer

        return when (question.type) {
            "single" -> {
                // answer 是字母，需要匹配选项
                val letter = answer.uppercase()
                options.filter { it.trimStart().uppercase().startsWith(letter) }
            }
            "multiple" -> {
                val letters = answer.split(",").map { it.trim().uppercase() }
                options.filter { opt ->
                    letters.any { opt.trimStart().uppercase().startsWith(it) }
                }
            }
            "judge" -> {
                // answer 是 "正确" 或 "错误"
                listOf(answer)
            }
            "essay" -> {
                // 参考答案文本
                listOf(answer)
            }
            else -> emptyList()
        }
    }

    /**
     * 解析选项 JSON 字符串为列表
     *
     * 使用 Android 内置 org.json 解析（不依赖 Gson 反射 / TypeToken），
     * 避免 R8 混淆后匿名 TypeToken 子类泛型签名被破坏导致解析返回空列表。
     */
    fun parseOptions(optionsJson: String): List<String> {
        if (optionsJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(optionsJson)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取答案显示文本
     */
    fun getAnswerDisplay(question: Question): String {
        val correct = getCorrectOptions(question)
        return correct.joinToString("；")
    }
}
