package com.iquestion.app.model

/**
 * JSON 题库数据结构（用于导入解析）
 * subject/chapter: 顶层默认科目/章节；科目或章节不存在时导入会自动创建
 */
data class JsonQuestionBank(
    val name: String? = null,
    val description: String? = null,
    val subject: String? = null,
    val chapter: String? = null,
    val questions: List<JsonQuestion> = emptyList()
)

/**
 * JSON 题目数据结构
 * type: single | multiple | judge | essay
 * answer: 可以是字符串（单选/判断）或字符串数组（多选）
 * chapter/subject: 可选，题目级指定科目与章节（备份导出使用；chapter 覆盖顶层默认章节）
 */
data class JsonQuestion(
    val type: String? = null,
    val question: String? = null,
    val options: List<String>? = null,
    val answer: Any? = null,
    val analysis: String? = null,
    val subject: String? = null,
    val chapter: String? = null,
    val isValid: Boolean? = null
)
