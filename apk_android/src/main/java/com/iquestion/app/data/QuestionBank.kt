package com.iquestion.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 题库实体
 */
@Entity(tableName = "question_banks")
data class QuestionBank(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val questionCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
