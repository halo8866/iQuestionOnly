package com.iquestion.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题目实体
 * type: single(单选) / multiple(多选) / judge(判断) / essay(解答)
 * options: JSON 数组字符串，如 ["A. 选项1","B. 选项2"]
 * answer: 对于 single/judge 为单个答案如 "A"；对于 multiple 为逗号分隔如 "A,B"；essay 为参考答案文本
 * chapterId: 所属章节（科目通过章节归属）
 * isValid: 是否有效（无效题目不参与练习/考试），默认 true
 */
@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBank::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bankId"]), Index(value = ["chapterId"])]
)
data class Question(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bankId: Long,
    val chapterId: Long = 0,
    val type: String,
    val question: String,
    val options: String,
    val answer: String,
    val analysis: String? = null,
    val isValid: Boolean = true,
    /** 练习作答次数（>0 表示已做过），用于章节进度展示；考试模式不计数 */
    val doneCount: Int = 0
) {
    /** 仅导入解析时使用：JSON 中题目所属章节名称，不落库 */
    @Ignore
    var chapterName: String? = null

    /** 仅导入解析时使用：JSON 中题目所属科目名称（备份文件），不落库 */
    @Ignore
    var subjectName: String? = null
}
