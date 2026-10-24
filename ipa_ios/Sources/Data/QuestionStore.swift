import Foundation

// MARK: - 数据访问层（Android QuestionDao 的完整移植）

/// 全局数据仓库。actor 保证串行访问；UI 层通过 `await QuestionStore.shared.xxx` 调用。
actor QuestionStore {
    static let shared = QuestionStore()
    private let db = Database.shared

    // ---- 题库操作（导入批次记录）----

    func getAllBanks() -> [QuestionBank] {
        var out: [QuestionBank] = []
        db.query("SELECT * FROM question_banks ORDER BY createdAt DESC") { r in
            out.append(QuestionBank(id: r.int64("id"), name: r.string("name"),
                                    description: r.optString("description"),
                                    questionCount: r.int("questionCount"), createdAt: r.int64("createdAt")))
        }
        return out
    }

    func getBankById(_ id: Int64) -> QuestionBank? {
        var out: QuestionBank?
        db.query("SELECT * FROM question_banks WHERE id = ? LIMIT 1", [.int(id)]) { r in
            out = QuestionBank(id: r.int64("id"), name: r.string("name"),
                               description: r.optString("description"),
                               questionCount: r.int("questionCount"), createdAt: r.int64("createdAt"))
        }
        return out
    }

    func insertBank(_ bank: QuestionBank) throws -> Int64 {
        try db.exec(
            "INSERT INTO question_banks (name, description, questionCount, createdAt) VALUES (?, ?, ?, ?)",
            [.text(bank.name),
             bank.description.map { .text($0) } ?? .null,
             .int(Int64(bank.questionCount)), .int(bank.createdAt)])
        return db.lastInsertId
    }

    func deleteBank(_ id: Int64) throws {
        try db.exec("DELETE FROM question_banks WHERE id = ?", [.int(id)])
    }

    func updateBank(_ bank: QuestionBank) throws {
        try db.exec(
            "UPDATE question_banks SET name = ?, description = ?, questionCount = ? WHERE id = ?",
            [.text(bank.name),
             bank.description.map { .text($0) } ?? .null,
             .int(Int64(bank.questionCount)), .int(bank.id)])
    }

    // ---- 题目操作 ----

    private func question(from r: Database.Row) -> Question {
        Question(id: r.int64("id"), bankId: r.int64("bankId"), chapterId: r.int64("chapterId"),
                 type: r.string("type"), question: r.string("question"), options: r.string("options"),
                 answer: r.string("answer"), analysis: r.optString("analysis"),
                 isValid: r.bool("isValid"), doneCount: r.int("doneCount"))
    }

    func getQuestionsByBank(_ bankId: Int64) -> [Question] {
        var out: [Question] = []
        db.query("SELECT * FROM questions WHERE bankId = ? ORDER BY id ASC", [.int(bankId)]) {
            out.append(question(from: $0))
        }
        return out
    }

    func getQuestionsByChapter(_ chapterId: Int64) -> [Question] {
        var out: [Question] = []
        db.query("SELECT * FROM questions WHERE isValid = 1 AND chapterId = ? ORDER BY id ASC", [.int(chapterId)]) {
            out.append(question(from: $0))
        }
        return out
    }

    /// 科目考试按所选章节抽题（仅有效题）
    func getValidQuestionsByChapters(_ chapterIds: [Int64]) -> [Question] {
        guard !chapterIds.isEmpty else { return [] }
        let ph = chapterIds.map { _ in "?" }.joined(separator: ",")
        var out: [Question] = []
        db.query("SELECT * FROM questions WHERE isValid = 1 AND chapterId IN (\(ph))",
                 chapterIds.map { .int($0) }) {
            out.append(question(from: $0))
        }
        return out
    }

    /// 按 ID 列表查询题目（恢复暂存考试时使用，已删除的题目不会返回）
    func getQuestionsByIds(_ ids: [Int64]) -> [Question] {
        guard !ids.isEmpty else { return [] }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        var out: [Question] = []
        db.query("SELECT * FROM questions WHERE id IN (\(ph))", ids.map { .int($0) }) {
            out.append(question(from: $0))
        }
        return out
    }

    /// 查询科目下所有有效题目（跨章节，用于科目考试抽题）
    func getValidQuestionsBySubject(_ subjectId: Int64) -> [Question] {
        var out: [Question] = []
        db.query("""
            SELECT q.* FROM questions q
            INNER JOIN chapters c ON q.chapterId = c.id
            WHERE c.subjectId = ? AND q.isValid = 1
            ORDER BY q.id ASC
            """, [.int(subjectId)]) {
            out.append(question(from: $0))
        }
        return out
    }

    func countQuestions(bankId: Int64) -> Int {
        scalar("SELECT COUNT(*) FROM questions WHERE bankId = ?", [.int(bankId)])
    }

    func insertQuestion(_ q: Question) throws -> Int64 {
        try db.exec(
            "INSERT INTO questions (bankId, chapterId, type, question, options, answer, analysis, isValid, doneCount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [.int(q.bankId), .int(q.chapterId), .text(q.type), .text(q.question),
             .text(q.options), .text(q.answer),
             q.analysis.map { .text($0) } ?? .null,
             .int(q.isValid ? 1 : 0), .int(Int64(q.doneCount))])
        return db.lastInsertId
    }

    func insertQuestions(_ qs: [Question]) throws -> [Int64] {
        var ids: [Int64] = []
        for q in qs { ids.append(try insertQuestion(q)) }
        return ids
    }

    /// 事务：插入题库及其所有题目，保证原子性
    func insertBankWithQuestions(_ bank: QuestionBank, _ questions: [Question]) throws -> Int64 {
        var bankId: Int64 = 0
        try db.transaction {
            bankId = try insertBank(bank)
            for var q in questions {
                q.bankId = bankId
                _ = try insertQuestion(q)
            }
        }
        return bankId
    }

    func deleteQuestionsByBank(_ bankId: Int64) throws {
        try db.exec("DELETE FROM questions WHERE bankId = ?", [.int(bankId)])
    }

    // ---- 题目管理（按科目维度）----

    func getQuestionById(_ id: Int64) -> Question? {
        var out: Question?
        db.query("SELECT * FROM questions WHERE id = ? LIMIT 1", [.int(id)]) {
            out = question(from: $0)
        }
        return out
    }

    func updateQuestion(_ q: Question) throws {
        try db.exec(
            "UPDATE questions SET bankId = ?, chapterId = ?, type = ?, question = ?, options = ?, answer = ?, analysis = ?, isValid = ?, doneCount = ? WHERE id = ?",
            [.int(q.bankId), .int(q.chapterId), .text(q.type), .text(q.question),
             .text(q.options), .text(q.answer),
             q.analysis.map { .text($0) } ?? .null,
             .int(q.isValid ? 1 : 0), .int(Int64(q.doneCount)), .int(q.id)])
    }

    func deleteQuestionById(_ id: Int64) throws {
        try db.exec("DELETE FROM questions WHERE id = ?", [.int(id)])
    }

    func deleteQuestionsByIds(_ ids: [Int64]) throws {
        guard !ids.isEmpty else { return }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        try db.exec("DELETE FROM questions WHERE id IN (\(ph))", ids.map { .int($0) })
    }

    /// 设置单题有效/无效标志（无效题目不参与练习/考试/错题练习），仅改状态不删除
    func setQuestionValid(_ id: Int64, _ valid: Bool) throws {
        try db.exec("UPDATE questions SET isValid = ? WHERE id = ?", [.int(valid ? 1 : 0), .int(id)])
    }

    /// 批量设置题目有效/无效标志
    func setQuestionsValid(_ ids: [Int64], _ valid: Bool) throws {
        guard !ids.isEmpty else { return }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        try db.exec("UPDATE questions SET isValid = ? WHERE id IN (\(ph))",
                    [.int(valid ? 1 : 0)] + ids.map { .int($0) })
    }

    private func questionWithChapter(from r: Database.Row) -> QuestionWithChapter {
        QuestionWithChapter(id: r.int64("id"), chapterId: r.int64("chapterId"),
                            chapterName: r.string("chapterName"), type: r.string("type"),
                            question: r.string("question"), options: r.string("options"),
                            answer: r.string("answer"), analysis: r.optString("analysis"),
                            isValid: r.bool("isValid"))
    }

    /// 查询科目下所有题目（含章节名，按章节再按题目 id 排序）
    func getSubjectQuestions(_ subjectId: Int64) -> [QuestionWithChapter] {
        var out: [QuestionWithChapter] = []
        db.query("""
            SELECT q.id AS id, q.chapterId AS chapterId, c.name AS chapterName,
                   q.type AS type, q.question AS question, q.options AS options,
                   q.answer AS answer, q.analysis AS analysis, q.isValid AS isValid
            FROM questions q
            INNER JOIN chapters c ON q.chapterId = c.id
            WHERE c.subjectId = ?
            ORDER BY c.id ASC, q.id ASC
            """, [.int(subjectId)]) {
            out.append(questionWithChapter(from: $0))
        }
        return out
    }

    /// 按章节筛选科目下题目
    func getSubjectQuestionsByChapter(_ subjectId: Int64, _ chapterId: Int64) -> [QuestionWithChapter] {
        var out: [QuestionWithChapter] = []
        db.query("""
            SELECT q.id AS id, q.chapterId AS chapterId, c.name AS chapterName,
                   q.type AS type, q.question AS question, q.options AS options,
                   q.answer AS answer, q.analysis AS analysis, q.isValid AS isValid
            FROM questions q
            INNER JOIN chapters c ON q.chapterId = c.id
            WHERE c.subjectId = ? AND q.chapterId = ?
            ORDER BY q.id ASC
            """, [.int(subjectId), .int(chapterId)]) {
            out.append(questionWithChapter(from: $0))
        }
        return out
    }

    func getChaptersBySubject(_ subjectId: Int64) -> [Chapter] {
        var out: [Chapter] = []
        db.query("SELECT * FROM chapters WHERE subjectId = ? ORDER BY id ASC", [.int(subjectId)]) { r in
            out.append(Chapter(id: r.int64("id"), subjectId: r.int64("subjectId"),
                               name: r.string("name"), createdAt: r.int64("createdAt")))
        }
        return out
    }

    // ---- 错题记录 ----

    /// 记录/刷新错题（同一题只保留最近一次错误）
    func upsertWrongRecord(questionId: Int64, wrongAnswer: String, wrongAt: Int64 = Date.nowMillis) throws {
        try db.exec("""
            INSERT INTO wrong_records (questionId, wrongAnswer, wrongAt) VALUES (?, ?, ?)
            ON CONFLICT(questionId) DO UPDATE SET wrongAnswer = excluded.wrongAnswer, wrongAt = excluded.wrongAt
            """, [.int(questionId), .text(wrongAnswer), .int(wrongAt)])
    }

    func findWrongRecord(_ questionId: Int64) -> WrongRecord? {
        var out: WrongRecord?
        db.query("SELECT * FROM wrong_records WHERE questionId = ? LIMIT 1", [.int(questionId)]) { r in
            out = WrongRecord(id: r.int64("id"), questionId: r.int64("questionId"),
                              wrongAnswer: r.string("wrongAnswer"), wrongAt: r.int64("wrongAt"))
        }
        return out
    }

    /// 答对后消除错题
    func deleteWrongByQuestion(_ questionId: Int64) throws {
        try db.exec("DELETE FROM wrong_records WHERE questionId = ?", [.int(questionId)])
    }

    /// 科目错题列表：有效错题在前（按出错时间倒序），无效错题置底
    func getWrongQuestionsBySubject(_ subjectId: Int64) -> [WrongQuestionItem] {
        var out: [WrongQuestionItem] = []
        db.query("""
            SELECT q.id AS id, q.bankId AS bankId, q.chapterId AS chapterId,
                   c.name AS chapterName, q.type AS type, q.question AS question,
                   q.options AS options, q.answer AS answer, q.analysis AS analysis,
                   w.wrongAnswer AS wrongAnswer, w.wrongAt AS wrongAt,
                   q.isValid AS isValid
            FROM wrong_records w
            INNER JOIN questions q ON q.id = w.questionId
            INNER JOIN chapters c ON q.chapterId = c.id
            WHERE c.subjectId = ?
            ORDER BY q.isValid DESC, w.wrongAt DESC
            """, [.int(subjectId)]) { r in
            out.append(WrongQuestionItem(id: r.int64("id"), bankId: r.int64("bankId"),
                chapterId: r.int64("chapterId"), chapterName: r.string("chapterName"),
                type: r.string("type"), question: r.string("question"), options: r.string("options"),
                answer: r.string("answer"), analysis: r.optString("analysis"),
                wrongAnswer: r.string("wrongAnswer"), wrongAt: r.int64("wrongAt"),
                isValid: r.bool("isValid")))
        }
        return out
    }

    // ---- 科目操作 ----

    func insertSubject(_ subject: Subject) throws -> Int64 {
        try db.exec("INSERT INTO subjects (name, createdAt) VALUES (?, ?)",
                    [.text(subject.name), .int(subject.createdAt)])
        return db.lastInsertId
    }

    func findSubjectByName(_ name: String) -> Subject? {
        var out: Subject?
        db.query("SELECT * FROM subjects WHERE name = ? LIMIT 1", [.text(name)]) { r in
            out = Subject(id: r.int64("id"), name: r.string("name"), createdAt: r.int64("createdAt"))
        }
        return out
    }

    func getSubjectById(_ id: Int64) -> Subject? {
        var out: Subject?
        db.query("SELECT * FROM subjects WHERE id = ? LIMIT 1", [.int(id)]) { r in
            out = Subject(id: r.int64("id"), name: r.string("name"), createdAt: r.int64("createdAt"))
        }
        return out
    }

    func getAllSubjectsWithCounts() -> [SubjectWithCounts] {
        var out: [SubjectWithCounts] = []
        db.query("""
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
            """) { r in
            out.append(SubjectWithCounts(id: r.int64("id"), name: r.string("name"),
                createdAt: r.int64("createdAt"), chapterCount: r.int("chapterCount"),
                questionCount: r.int("questionCount"), wrongCount: r.int("wrongCount")))
        }
        return out
    }

    func deleteSubject(_ id: Int64) throws {
        try db.exec("DELETE FROM subjects WHERE id = ?", [.int(id)])
    }

    // ---- 章节操作 ----

    func insertChapter(_ chapter: Chapter) throws -> Int64 {
        try db.exec("INSERT INTO chapters (subjectId, name, createdAt) VALUES (?, ?, ?)",
                    [.int(chapter.subjectId), .text(chapter.name), .int(chapter.createdAt)])
        return db.lastInsertId
    }

    func findChapter(_ subjectId: Int64, _ name: String) -> Chapter? {
        var out: Chapter?
        db.query("SELECT * FROM chapters WHERE subjectId = ? AND name = ? LIMIT 1",
                 [.int(subjectId), .text(name)]) { r in
            out = Chapter(id: r.int64("id"), subjectId: r.int64("subjectId"),
                          name: r.string("name"), createdAt: r.int64("createdAt"))
        }
        return out
    }

    func getChaptersWithCounts(_ subjectId: Int64) -> [ChapterWithCounts] {
        var out: [ChapterWithCounts] = []
        db.query("""
            SELECT c.*,
                (SELECT COUNT(*) FROM questions q WHERE q.chapterId = c.id AND q.isValid = 1) AS questionCount,
                (SELECT COUNT(*) FROM questions q WHERE q.chapterId = c.id AND q.isValid = 1 AND q.doneCount > 0) AS doneCount
            FROM chapters c
            WHERE c.subjectId = ?
            ORDER BY c.createdAt ASC
            """, [.int(subjectId)]) { r in
            out.append(ChapterWithCounts(id: r.int64("id"), subjectId: r.int64("subjectId"),
                name: r.string("name"), createdAt: r.int64("createdAt"),
                questionCount: r.int("questionCount"), doneCount: r.int("doneCount")))
        }
        return out
    }

    /// 标记题目已做过一次（练习作答时调用；doneCount>0 即视为已做）
    func markQuestionDone(_ id: Int64) throws {
        try db.exec("UPDATE questions SET doneCount = doneCount + 1 WHERE id = ?", [.int(id)])
    }

    func deleteChapter(_ id: Int64) throws {
        try db.exec("DELETE FROM chapters WHERE id = ?", [.int(id)])
    }

    // ---- 考试历史 ----

    func insertExamRecord(_ record: ExamRecord) throws -> Int64 {
        try db.exec(
            "INSERT INTO exam_records (title, totalCount, correctCount, createdAt, isValid) VALUES (?, ?, ?, ?, ?)",
            [.text(record.title), .int(Int64(record.totalCount)), .int(Int64(record.correctCount)),
             .int(record.createdAt), .int(record.isValid ? 1 : 0)])
        return db.lastInsertId
    }

    func insertExamAnswers(_ answers: [ExamAnswer]) throws {
        for a in answers {
            try db.exec(
                "INSERT INTO exam_answers (recordId, orderIndex, type, question, options, answer, analysis, userAnswer, isCorrect) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [.int(a.recordId), .int(Int64(a.orderIndex)), .text(a.type), .text(a.question),
                 .text(a.options), .text(a.answer),
                 a.analysis.map { .text($0) } ?? .null,
                 .text(a.userAnswer), .int(a.isCorrect ? 1 : 0)])
        }
    }

    /// 事务：保存一次考试记录及其全部题目快照
    func insertExamWithAnswers(_ record: ExamRecord, _ answers: [ExamAnswer]) throws -> Int64 {
        var recordId: Int64 = 0
        try db.transaction {
            recordId = try insertExamRecord(record)
            try insertExamAnswers(answers.map { a in var a = a; a.recordId = recordId; return a })
        }
        return recordId
    }

    /// 全部考试记录（含有效与无效），按时间倒序
    func getExamRecords() -> [ExamRecord] {
        var out: [ExamRecord] = []
        db.query("SELECT * FROM exam_records ORDER BY createdAt DESC") { r in
            out.append(examRecord(from: r))
        }
        return out
    }

    private func examRecord(from r: Database.Row) -> ExamRecord {
        ExamRecord(id: r.int64("id"), title: r.string("title"), totalCount: r.int("totalCount"),
                   correctCount: r.int("correctCount"), createdAt: r.int64("createdAt"),
                   isValid: r.bool("isValid"))
    }

    /// 已清空（软删除）的考试记录，数据仍保留在库中
    func getClearedExamRecords() -> [ExamRecord] {
        var out: [ExamRecord] = []
        db.query("SELECT * FROM exam_records WHERE isValid = 0 ORDER BY createdAt DESC") { r in
            out.append(examRecord(from: r))
        }
        return out
    }

    func getExamRecordById(_ id: Int64) -> ExamRecord? {
        var out: ExamRecord?
        db.query("SELECT * FROM exam_records WHERE id = ? LIMIT 1", [.int(id)]) { r in
            out = examRecord(from: r)
        }
        return out
    }

    func getExamAnswers(_ recordId: Int64) -> [ExamAnswer] {
        var out: [ExamAnswer] = []
        db.query("SELECT * FROM exam_answers WHERE recordId = ? ORDER BY orderIndex ASC", [.int(recordId)]) { r in
            out.append(ExamAnswer(id: r.int64("id"), recordId: r.int64("recordId"),
                orderIndex: r.int("orderIndex"), type: r.string("type"),
                question: r.string("question"), options: r.string("options"),
                answer: r.string("answer"), analysis: r.optString("analysis"),
                userAnswer: r.string("userAnswer"), isCorrect: r.bool("isCorrect")))
        }
        return out
    }

    /// 设置单条考试记录的有效/无效标志（仅改状态，不删除数据）
    func setExamRecordValid(_ id: Int64, _ valid: Bool) throws {
        try db.exec("UPDATE exam_records SET isValid = ? WHERE id = ?", [.int(valid ? 1 : 0), .int(id)])
    }

    /// 批量设置考试记录的有效/无效标志
    func setExamRecordsValid(_ ids: [Int64], _ valid: Bool) throws {
        guard !ids.isEmpty else { return }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        try db.exec("UPDATE exam_records SET isValid = ? WHERE id IN (\(ph))",
                    [.int(valid ? 1 : 0)] + ids.map { .int($0) })
    }

    /// 软删除单条考试记录：仅置 isValid = 0，数据保留在库中
    func deleteExamRecord(_ id: Int64) throws {
        try db.exec("UPDATE exam_records SET isValid = 0 WHERE id = ?", [.int(id)])
    }

    /// 批量软删除考试记录
    func deleteExamRecordsByIds(_ ids: [Int64]) throws {
        guard !ids.isEmpty else { return }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        try db.exec("UPDATE exam_records SET isValid = 0 WHERE id IN (\(ph))", ids.map { .int($0) })
    }

    /// 恢复已软删除的考试记录
    func restoreExamRecord(_ id: Int64) throws {
        try db.exec("UPDATE exam_records SET isValid = 1 WHERE id = ?", [.int(id)])
    }

    /// 软删除全部考试记录
    func deleteAllExamRecords() throws {
        try db.exec("UPDATE exam_records SET isValid = 0")
    }

    /// 物理删除单条考试记录：作答快照随外键级联一并删除，不可恢复
    func deleteExamRecordPhysically(_ id: Int64) throws {
        try db.exec("DELETE FROM exam_records WHERE id = ?", [.int(id)])
    }

    /// 物理删除多条考试记录
    func deleteExamRecordsPhysically(_ ids: [Int64]) throws {
        guard !ids.isEmpty else { return }
        let ph = ids.map { _ in "?" }.joined(separator: ",")
        try db.exec("DELETE FROM exam_records WHERE id IN (\(ph))", ids.map { .int($0) })
    }

    // ---- 整库清空（导入「清空后导入」模式使用）----

    /// 清空本机全部数据，按子表→父表顺序删除以避免外键约束冲突，整体原子执行
    func clearAllData() throws {
        try db.transaction {
            try db.exec("DELETE FROM exam_answers")
            try db.exec("DELETE FROM exam_records")
            try db.exec("DELETE FROM wrong_records")
            try db.exec("DELETE FROM questions")
            try db.exec("DELETE FROM chapters")
            try db.exec("DELETE FROM subjects")
            try db.exec("DELETE FROM question_banks")
        }
    }

    // ---- 内部工具 ----

    private func scalar(_ sql: String, _ params: [SQLValue] = []) -> Int {
        var out = 0
        db.query(sql, params) { out = $0.int("COUNT(*)") }
        return out
    }
}
