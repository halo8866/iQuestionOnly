package com.iquestion.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 考试历史记录（一次交卷生成一条）
 * title: 考试名称（科目名 / 章节名）
 * totalCount/correctCount: 题目总数 / 答对题数
 * createdAt: 交卷时间
 * isValid: 是否有效（删除为软删除，仅置为 false，数据保留在库中）
 */
@Entity(tableName = "exam_records")
data class ExamRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val totalCount: Int,
    val correctCount: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1")
    val isValid: Boolean = true
)

/**
 * 考试记录中的单题快照：题目、选项、正确答案、用户作答、正误
 * 快照保存，之后题库中题目被修改/删除也不影响历史回顾
 */
@Entity(
    tableName = "exam_answers",
    foreignKeys = [
        ForeignKey(
            entity = ExamRecord::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordId"])]
)
data class ExamAnswer(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val recordId: Long,
    val orderIndex: Int,
    val type: String,
    val question: String,
    val options: String,
    val answer: String,
    val analysis: String?,
    val userAnswer: String,
    val isCorrect: Boolean
)
