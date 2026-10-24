package com.iquestion.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QuestionBank::class, Question::class, Subject::class, Chapter::class, WrongRecord::class,
        ExamRecord::class, ExamAnswer::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v5 → v6：exam_records 新增软删除标志 isValid，保留已有数据 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_records ADD COLUMN isValid INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v6 → v7：questions 新增练习作答次数 doneCount，保留已有数据 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN doneCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iquestion.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    // 更早版本（v5 之前）结构差异大，开发版本直接重建旧库
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
