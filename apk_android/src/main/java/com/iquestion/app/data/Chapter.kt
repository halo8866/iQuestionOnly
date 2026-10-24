package com.iquestion.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节实体（属于某个科目，名称自定义，如「第一章」「数据库」）
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Subject::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["subjectId", "name"], unique = true)
    ]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: Long,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 科目 + 章节数/题目数/错题数（列表展示用）
 */
data class SubjectWithCounts(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val chapterCount: Int,
    val questionCount: Int,
    val wrongCount: Int
)

/**
 * 章节 + 题目数/已做题数（列表展示用）
 */
data class ChapterWithCounts(
    val id: Long,
    val subjectId: Long,
    val name: String,
    val createdAt: Long,
    val questionCount: Int,
    val doneCount: Int = 0
)

/**
 * 题目 + 所属章节名（科目题目管理列表用）
 */
data class QuestionWithChapter(
    val id: Long,
    val chapterId: Long,
    val chapterName: String,
    val type: String,
    val question: String,
    val options: String,
    val answer: String,
    val analysis: String?,
    val isValid: Boolean
)
