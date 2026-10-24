package com.iquestion.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface QuestionDao {

    // ---- 题库操作（导入批次记录）----
    @Query("SELECT * FROM question_banks ORDER BY createdAt DESC")
    suspend fun getAllBanks(): List<QuestionBank>

    @Query("SELECT * FROM question_banks WHERE id = :id")
    suspend fun getBankById(id: Long): QuestionBank?

    @Insert
    suspend fun insertBank(bank: QuestionBank): Long

    @Query("DELETE FROM question_banks WHERE id = :id")
    suspend fun deleteBank(id: Long)

    @Update
    suspend fun updateBank(bank: QuestionBank)

    // ---- 题目操作 ----
    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY id ASC")
    suspend fun getQuestionsByBank(bankId: Long): List<Question>

    @Query("SELECT * FROM questions WHERE isValid = 1 AND chapterId = :chapterId ORDER BY id ASC")
    suspend fun getQuestionsByChapter(chapterId: Long): List<Question>

    /** 科目考试按所选章节抽题（仅有效题） */
    @Query("SELECT * FROM questions WHERE isValid = 1 AND chapterId IN (:chapterIds)")
    suspend fun getValidQuestionsByChapters(chapterIds: List<Long>): List<Question>

    /** 按 ID 列表查询题目（恢复暂存考试时使用，已删除的题目不会返回） */
    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun getQuestionsByIds(ids: List<Long>): List<Question>

    /**
     * 查询科目下所有有效题目（跨章节，用于科目考试抽题）
     */
    @Query(
        """
        SELECT q.* FROM questions q
        INNER JOIN chapters c ON q.chapterId = c.id
        WHERE c.subjectId = :subjectId AND q.isValid = 1
        ORDER BY q.id ASC
        """
    )
    suspend fun getValidQuestionsBySubject(subjectId: Long): List<Question>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    suspend fun countQuestions(bankId: Long): Int

    @Insert
    suspend fun insertQuestion(question: Question): Long

    @Insert
    suspend fun insertQuestions(questions: List<Question>): List<Long>

    /**
     * 事务：插入题库及其所有题目，保证原子性
     */
    @Transaction
    suspend fun insertBankWithQuestions(bank: QuestionBank, questions: List<Question>): Long {
        val bankId = insertBank(bank)
        val questionsWithBankId = questions.map { it.copy(bankId = bankId) }
        insertQuestions(questionsWithBankId)
        return bankId
    }

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteQuestionsByBank(bankId: Long)

    // ---- 题目管理（按科目维度）----
    @Query("SELECT * FROM questions WHERE id = :id LIMIT 1")
    suspend fun getQuestionById(id: Long): Question?

    @Update
    suspend fun updateQuestion(question: Question)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteQuestionById(id: Long)

    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun deleteQuestionsByIds(ids: List<Long>)

    /** 设置单题有效/无效标志（无效题目不参与顺序练习、考试、错题练习），仅改状态不删除 */
    @Query("UPDATE questions SET isValid = :valid WHERE id = :id")
    suspend fun setQuestionValid(id: Long, valid: Boolean)

    /** 批量设置题目有效/无效标志，仅改状态不删除 */
    @Query("UPDATE questions SET isValid = :valid WHERE id IN (:ids)")
    suspend fun setQuestionsValid(ids: List<Long>, valid: Boolean)

    /**
     * 查询科目下所有题目（含章节名，按章节再按题目 id 排序）
     */
    @Query(
        """
        SELECT q.id AS id, q.chapterId AS chapterId, c.name AS chapterName,
               q.type AS type, q.question AS question, q.options AS options,
               q.answer AS answer, q.analysis AS analysis, q.isValid AS isValid
        FROM questions q
        INNER JOIN chapters c ON q.chapterId = c.id
        WHERE c.subjectId = :subjectId
        ORDER BY c.id ASC, q.id ASC
        """
    )
    suspend fun getSubjectQuestions(subjectId: Long): List<QuestionWithChapter>

    /**
     * 按章节筛选科目下题目
     */
    @Query(
        """
        SELECT q.id AS id, q.chapterId AS chapterId, c.name AS chapterName,
               q.type AS type, q.question AS question, q.options AS options,
               q.answer AS answer, q.analysis AS analysis, q.isValid AS isValid
        FROM questions q
        INNER JOIN chapters c ON q.chapterId = c.id
        WHERE c.subjectId = :subjectId AND q.chapterId = :chapterId
        ORDER BY q.id ASC
        """
    )
    suspend fun getSubjectQuestionsByChapter(subjectId: Long, chapterId: Long): List<QuestionWithChapter>

    @Query("SELECT * FROM chapters WHERE subjectId = :subjectId ORDER BY id ASC")
    suspend fun getChaptersBySubject(subjectId: Long): List<Chapter>

    // ---- 错题记录 ----
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertWrongRecord(record: WrongRecord)

    @Query("SELECT * FROM wrong_records WHERE questionId = :questionId LIMIT 1")
    suspend fun findWrongRecord(questionId: Long): WrongRecord?

    @Query("UPDATE wrong_records SET wrongAnswer = :wrongAnswer, wrongAt = :wrongAt WHERE questionId = :questionId")
    suspend fun updateWrongRecord(questionId: Long, wrongAnswer: String, wrongAt: Long)

    /**
     * 记录/刷新错题（同一题只保留最近一次错误）
     */
    @Transaction
    suspend fun upsertWrongRecord(questionId: Long, wrongAnswer: String, wrongAt: Long) {
        if (findWrongRecord(questionId) != null) {
            updateWrongRecord(questionId, wrongAnswer, wrongAt)
        } else {
            insertWrongRecord(WrongRecord(questionId = questionId, wrongAnswer = wrongAnswer, wrongAt = wrongAt))
        }
    }

    /**
     * 答对后消除错题
     */
    @Query("DELETE FROM wrong_records WHERE questionId = :questionId")
    suspend fun deleteWrongByQuestion(questionId: Long)

    /**
     * 科目错题列表：题目信息 + 章节名 + 错误作答。
     * 有效错题在前（按出错时间倒序），无效错题置底（按出错时间倒序）。
     */
    @Query(
        """
        SELECT q.id AS id, q.bankId AS bankId, q.chapterId AS chapterId,
               c.name AS chapterName, q.type AS type, q.question AS question,
               q.options AS options, q.answer AS answer, q.analysis AS analysis,
               w.wrongAnswer AS wrongAnswer, w.wrongAt AS wrongAt,
               q.isValid AS isValid
        FROM wrong_records w
        INNER JOIN questions q ON q.id = w.questionId
        INNER JOIN chapters c ON q.chapterId = c.id
        WHERE c.subjectId = :subjectId
        ORDER BY q.isValid DESC, w.wrongAt DESC
        """
    )
    suspend fun getWrongQuestionsBySubject(subjectId: Long): List<WrongQuestionItem>

    // ---- 科目操作 ----
    @Insert
    suspend fun insertSubject(subject: Subject): Long

    @Query("SELECT * FROM subjects WHERE name = :name LIMIT 1")
    suspend fun findSubjectByName(name: String): Subject?

    @Query("SELECT * FROM subjects WHERE id = :id LIMIT 1")
    suspend fun getSubjectById(id: Long): Subject?

    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM chapters c WHERE c.subjectId = s.id) AS chapterCount,
            (SELECT COUNT(*) FROM chapters c
                INNER JOIN questions q ON q.chapterId = c.id
                WHERE c.subjectId = s.id AND q.isValid = 1) AS questionCount,
            (SELECT COUNT(*) FROM wrong_records w
                INNER JOIN questions q ON q.id = w.questionId
                INNER JOIN chapters c ON q.chapterId = c.id
                WHERE c.subjectId = s.id AND q.isValid = 1) AS wrongCount
        FROM subjects s
        ORDER BY s.createdAt ASC
        """
    )
    suspend fun getAllSubjectsWithCounts(): List<SubjectWithCounts>

    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun deleteSubject(id: Long)

    // ---- 章节操作 ----
    @Insert
    suspend fun insertChapter(chapter: Chapter): Long

    @Query("SELECT * FROM chapters WHERE subjectId = :subjectId AND name = :name LIMIT 1")
    suspend fun findChapter(subjectId: Long, name: String): Chapter?

    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM questions q WHERE q.chapterId = c.id AND q.isValid = 1) AS questionCount,
            (SELECT COUNT(*) FROM questions q WHERE q.chapterId = c.id AND q.isValid = 1 AND q.doneCount > 0) AS doneCount
        FROM chapters c
        WHERE c.subjectId = :subjectId
        ORDER BY c.createdAt ASC
        """
    )
    suspend fun getChaptersWithCounts(subjectId: Long): List<ChapterWithCounts>

    /** 标记题目已做过一次（练习作答时调用；doneCount>0 即视为已做） */
    @Query("UPDATE questions SET doneCount = doneCount + 1 WHERE id = :id")
    suspend fun markQuestionDone(id: Long)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun deleteChapter(id: Long)

    // ---- 考试历史 ----
    @Insert
    suspend fun insertExamRecord(record: ExamRecord): Long

    @Insert
    suspend fun insertExamAnswers(answers: List<ExamAnswer>)

    /**
     * 事务：保存一次考试记录及其全部题目快照
     */
    @Transaction
    suspend fun insertExamWithAnswers(record: ExamRecord, answers: List<ExamAnswer>): Long {
        val recordId = insertExamRecord(record)
        insertExamAnswers(answers.map { it.copy(recordId = recordId) })
        return recordId
    }

    /** 全部考试记录（含有效与无效），按时间倒序；有效/无效仅为标记，数据都保留在库中 */
    @Query("SELECT * FROM exam_records ORDER BY createdAt DESC")
    suspend fun getExamRecords(): List<ExamRecord>

    /** 已清空（软删除）的考试记录，数据仍保留在库中 */
    @Query("SELECT * FROM exam_records WHERE isValid = 0 ORDER BY createdAt DESC")
    suspend fun getClearedExamRecords(): List<ExamRecord>

    @Query("SELECT * FROM exam_records WHERE id = :id LIMIT 1")
    suspend fun getExamRecordById(id: Long): ExamRecord?

    @Query("SELECT * FROM exam_answers WHERE recordId = :recordId ORDER BY orderIndex ASC")
    suspend fun getExamAnswers(recordId: Long): List<ExamAnswer>

    /** 设置单条考试记录的有效/无效标志（仅改状态，不删除数据） */
    @Query("UPDATE exam_records SET isValid = :valid WHERE id = :id")
    suspend fun setExamRecordValid(id: Long, valid: Boolean)

    /** 批量设置考试记录的有效/无效标志（仅改状态，不删除数据） */
    @Query("UPDATE exam_records SET isValid = :valid WHERE id IN (:ids)")
    suspend fun setExamRecordsValid(ids: List<Long>, valid: Boolean)

    /** 软删除单条考试记录：仅置 isValid = 0，数据保留在库中 */
    @Query("UPDATE exam_records SET isValid = 0 WHERE id = :id")
    suspend fun deleteExamRecord(id: Long)

    /** 批量软删除考试记录：仅置 isValid = 0，数据保留在库中 */
    @Query("UPDATE exam_records SET isValid = 0 WHERE id IN (:ids)")
    suspend fun deleteExamRecordsByIds(ids: List<Long>)

    /** 恢复已软删除的考试记录：isValid 置回 1，重新显示在考试管理中 */
    @Query("UPDATE exam_records SET isValid = 1 WHERE id = :id")
    suspend fun restoreExamRecord(id: Long)

    /** 软删除全部考试记录：仅置 isValid = 0，数据保留在库中 */
    @Query("UPDATE exam_records SET isValid = 0")
    suspend fun deleteAllExamRecords()

    /** 物理删除单条考试记录：作答快照随外键级联一并删除，不可恢复 */
    @Query("DELETE FROM exam_records WHERE id = :id")
    suspend fun deleteExamRecordPhysically(id: Long)

    /** 物理删除多条考试记录：作答快照随外键级联一并删除，不可恢复 */
    @Query("DELETE FROM exam_records WHERE id IN (:ids)")
    suspend fun deleteExamRecordsPhysically(ids: List<Long>)

    // ---- 整库清空（导入「清空后导入」模式使用）----

    @Query("DELETE FROM exam_answers")
    suspend fun clearExamAnswers()

    @Query("DELETE FROM exam_records")
    suspend fun clearExamRecords()

    @Query("DELETE FROM wrong_records")
    suspend fun clearWrongRecords()

    @Query("DELETE FROM questions")
    suspend fun clearQuestions()

    @Query("DELETE FROM chapters")
    suspend fun clearChapters()

    @Query("DELETE FROM subjects")
    suspend fun clearSubjects()

    @Query("DELETE FROM question_banks")
    suspend fun clearQuestionBanks()

    /**
     * 事务：清空本机全部数据（考试记录/作答快照、错题、题目、章节、科目、导入批次）。
     * 按子表→父表顺序删除以避免外键约束冲突，整体原子执行。
     */
    @Transaction
    suspend fun clearAllData() {
        clearExamAnswers()
        clearExamRecords()
        clearWrongRecords()
        clearQuestions()
        clearChapters()
        clearSubjects()
        clearQuestionBanks()
    }
}
