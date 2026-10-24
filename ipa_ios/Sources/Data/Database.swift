import Foundation
import SQLite3

// MARK: - SQL 参数值

enum SQLValue {
    case int(Int64)
    case text(String)
    case null
}

// MARK: - SQLite 底层封装

/// SQLite 数据库封装。仅由 QuestionStore（actor）访问，天然串行，FULLMUTEX 兜底。
/// 建表结构与 Android Room AppDatabase v7 完全一致，两平台数据语义互通。
final class Database {
    static let shared = Database()

    private var db: OpaquePointer?
    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        open()
        createSchema()
    }

    private func open() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("iquestion.db").path
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK else {
            fatalError("无法打开数据库: \(path)")
        }
        db = handle
        execRaw("PRAGMA foreign_keys = ON")
        execRaw("PRAGMA journal_mode = WAL")
    }

    deinit {
        if let db { sqlite3_close_v2(db) }
    }

    /// 建表（与 Room v7 对齐；幂等）
    private func createSchema() {
        execRaw("""
        CREATE TABLE IF NOT EXISTS question_banks (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            description TEXT, questionCount INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)
        """)
        execRaw("""
        CREATE TABLE IF NOT EXISTS subjects (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, createdAt INTEGER NOT NULL)
        """)
        execRaw("CREATE UNIQUE INDEX IF NOT EXISTS index_subjects_name ON subjects (name)")
        execRaw("""
        CREATE TABLE IF NOT EXISTS chapters (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
            name TEXT NOT NULL, createdAt INTEGER NOT NULL)
        """)
        execRaw("CREATE INDEX IF NOT EXISTS index_chapters_subjectId ON chapters (subjectId)")
        execRaw("CREATE UNIQUE INDEX IF NOT EXISTS index_chapters_subjectId_name ON chapters (subjectId, name)")
        execRaw("""
        CREATE TABLE IF NOT EXISTS questions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            bankId INTEGER NOT NULL REFERENCES question_banks(id) ON DELETE CASCADE,
            chapterId INTEGER NOT NULL DEFAULT 0 REFERENCES chapters(id) ON DELETE CASCADE,
            type TEXT NOT NULL, question TEXT NOT NULL, options TEXT NOT NULL, answer TEXT NOT NULL,
            analysis TEXT, isValid INTEGER NOT NULL DEFAULT 1, doneCount INTEGER NOT NULL DEFAULT 0)
        """)
        execRaw("CREATE INDEX IF NOT EXISTS index_questions_bankId ON questions (bankId)")
        execRaw("CREATE INDEX IF NOT EXISTS index_questions_chapterId ON questions (chapterId)")
        execRaw("""
        CREATE TABLE IF NOT EXISTS wrong_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            questionId INTEGER NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
            wrongAnswer TEXT NOT NULL, wrongAt INTEGER NOT NULL)
        """)
        execRaw("CREATE UNIQUE INDEX IF NOT EXISTS index_wrong_records_questionId ON wrong_records (questionId)")
        execRaw("""
        CREATE TABLE IF NOT EXISTS exam_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
            totalCount INTEGER NOT NULL, correctCount INTEGER NOT NULL,
            createdAt INTEGER NOT NULL, isValid INTEGER NOT NULL DEFAULT 1)
        """)
        execRaw("""
        CREATE TABLE IF NOT EXISTS exam_answers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recordId INTEGER NOT NULL REFERENCES exam_records(id) ON DELETE CASCADE,
            orderIndex INTEGER NOT NULL, type TEXT NOT NULL, question TEXT NOT NULL,
            options TEXT NOT NULL, answer TEXT NOT NULL, analysis TEXT,
            userAnswer TEXT NOT NULL, isCorrect INTEGER NOT NULL DEFAULT 0)
        """)
        execRaw("CREATE INDEX IF NOT EXISTS index_exam_answers_recordId ON exam_answers (recordId)")
    }

    // MARK: 基础操作

    /// 执行无结果语句（建表/PRAGMA）
    func execRaw(_ sql: String) {
        var errMsg: UnsafeMutablePointer<CChar>?
        sqlite3_exec(db, sql, nil, nil, &errMsg)
        if let errMsg { sqlite3_free(errMsg) }
    }

    /// 执行带参语句，返回受影响行数
    @discardableResult
    func exec(_ sql: String, _ params: [SQLValue] = []) throws -> Int {
        let stmt = try prepare(sql, params)
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw DBError.step(String(cString: sqlite3_errmsg(db)))
        }
        return Int(sqlite3_changes(db))
    }

    /// 查询并逐行回调
    func query(_ sql: String, _ params: [SQLValue] = [], row: (Row) -> Void) {
        let stmt: OpaquePointer
        do { stmt = try prepare(sql, params) } catch { return }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            row(Row(stmt: stmt))
        }
    }

    /// 事务执行（BEGIN…COMMIT，异常时 ROLLBACK）
    func transaction(_ body: () throws -> Void) throws {
        execRaw("BEGIN IMMEDIATE")
        do {
            try body()
            execRaw("COMMIT")
        } catch {
            execRaw("ROLLBACK")
            throw error
        }
    }

    var lastInsertId: Int64 { sqlite3_last_insert_rowid(db) }

    private func prepare(_ sql: String, _ params: [SQLValue]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw DBError.prepare(String(cString: sqlite3_errmsg(db)))
        }
        for (i, p) in params.enumerated() {
            let idx = Int32(i + 1)
            switch p {
            case .int(let v): sqlite3_bind_int64(stmt, idx, v)
            case .text(let v): sqlite3_bind_text(stmt, idx, v, -1, Self.SQLITE_TRANSIENT)
            case .null: sqlite3_bind_null(stmt, idx)
            }
        }
        return stmt
    }

    // MARK: 行读取器

    struct Row {
        let stmt: OpaquePointer

        func int64(_ col: String) -> Int64 {
            if let v = optInt64(col) { return v }
            return 0
        }

        func int(_ col: String) -> Int { Int(int64(col)) }

        func bool(_ col: String) -> Bool { int64(col) != 0 }

        func string(_ col: String) -> String {
            if let c = sqlite3_column_text(stmt, index(col)) { return String(cString: c) }
            return ""
        }

        func optString(_ col: String) -> String? {
            let i = index(col)
            guard i >= 0, sqlite3_column_type(stmt, i) != SQLITE_NULL,
                  let c = sqlite3_column_text(stmt, i) else { return nil }
            return String(cString: c)
        }

        private func optInt64(_ col: String) -> Int64? {
            let i = index(col)
            guard i >= 0, sqlite3_column_type(stmt, i) != SQLITE_NULL else { return nil }
            return sqlite3_column_int64(stmt, i)
        }

        /// 按列名扫描列索引（SQLite C API 无按名取索引函数）；找不到返回 -1
        private func index(_ col: String) -> Int32 {
            let count = sqlite3_column_count(stmt)
            var i: Int32 = 0
            while i < count {
                if let name = sqlite3_column_name(stmt, i), strcmp(name, col) == 0 { return i }
                i += 1
            }
            return -1
        }
    }

    enum DBError: Error, LocalizedError {
        case prepare(String)
        case step(String)
        var errorDescription: String? {
            switch self {
            case .prepare(let m), .step(let m): return m
            }
        }
    }
}
