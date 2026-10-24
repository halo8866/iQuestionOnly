package com.iquestion.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 错题记录：每次练习判错的题目（同一题只保留最近一次错误）
 * wrongAnswer: 用户当时的错误作答（选项文本用「；」拼接；解答题为所填文本；未作答为「未作答」）
 */
@Entity(
    tableName = "wrong_records",
    foreignKeys = [
        ForeignKey(
            entity = Question::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["questionId"], unique = true)]
)
data class WrongRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val questionId: Long,
    val wrongAnswer: String,
    val wrongAt: Long = System.currentTimeMillis()
)

/**
 * 错题列表项：题目信息 + 章节名 + 错误作答（科目错题本用）
 */
data class WrongQuestionItem(
    val id: Long,
    val bankId: Long,
    val chapterId: Long,
    val chapterName: String,
    val type: String,
    val question: String,
    val options: String,
    val answer: String,
    val analysis: String?,
    val wrongAnswer: String,
    val wrongAt: Long,
    val isValid: Boolean
)
