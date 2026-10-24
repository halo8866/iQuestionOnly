package com.iquestion.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 科目实体（手动添加，如「计算机基础」「高等数学」）
 */
@Entity(
    tableName = "subjects",
    indices = [Index(value = ["name"], unique = true)]
)
data class Subject(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
