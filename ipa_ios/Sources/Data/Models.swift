import Foundation

// MARK: - 数据模型（与 Android/Room 数据库结构一一对应）

/// 题库实体（导入批次记录）
struct QuestionBank: Identifiable, Hashable {
    var id: Int64 = 0
    var name: String
    var description: String?
    var questionCount: Int = 0
    var createdAt: Int64 = Date.nowMillis
}

/// 科目实体
struct Subject: Identifiable, Hashable {
    var id: Int64 = 0
    var name: String
    var createdAt: Int64 = Date.nowMillis
}

/// 章节实体（属于某个科目）
struct Chapter: Identifiable, Hashable {
    var id: Int64 = 0
    var subjectId: Int64
    var name: String
    var createdAt: Int64 = Date.nowMillis
}

/// 题目实体
/// type: single(单选) / multiple(多选) / judge(判断) / essay(解答)
/// options: JSON 数组字符串；answer: 单选/判断为 "A"，多选逗号分隔 "A,B"，解答为文本
struct Question: Identifiable, Hashable {
    var id: Int64 = 0
    var bankId: Int64
    var chapterId: Int64 = 0
    var type: String
    var question: String
    var options: String
    var answer: String
    var analysis: String?
    var isValid: Bool = true
    /// 练习作答次数（>0 表示已做过），用于章节进度展示；考试模式不计数
    var doneCount: Int = 0
    /// 仅导入解析时使用：JSON 中题目所属章节名称，不落库
    var chapterName: String? = nil
    /// 仅导入解析时使用：JSON 中题目所属科目名称（备份文件），不落库
    var subjectName: String? = nil
}

/// 错题记录（同一题只保留最近一次错误）
struct WrongRecord: Hashable {
    var id: Int64 = 0
    var questionId: Int64
    var wrongAnswer: String
    var wrongAt: Int64 = Date.nowMillis
}

/// 考试历史记录（一次交卷生成一条；删除为软删除 isValid=false）
struct ExamRecord: Identifiable, Hashable {
    var id: Int64 = 0
    var title: String
    var totalCount: Int
    var correctCount: Int
    var createdAt: Int64 = Date.nowMillis
    var isValid: Bool = true
}

/// 考试记录中的单题快照
struct ExamAnswer: Hashable {
    var id: Int64 = 0
    var recordId: Int64 = 0
    var orderIndex: Int
    var type: String
    var question: String
    var options: String
    var answer: String
    var analysis: String?
    var userAnswer: String
    var isCorrect: Bool
}

// MARK: - 列表查询结果

/// 科目 + 章节数/题目数/错题数（列表展示用）
struct SubjectWithCounts: Identifiable, Hashable {
    let id: Int64
    let name: String
    let createdAt: Int64
    let chapterCount: Int
    let questionCount: Int
    let wrongCount: Int
}

/// 章节 + 题目数/已做题数（列表展示用）
struct ChapterWithCounts: Identifiable, Hashable {
    let id: Int64
    let subjectId: Int64
    let name: String
    let createdAt: Int64
    let questionCount: Int
    let doneCount: Int
}

/// 题目 + 所属章节名（科目题目管理列表用）
struct QuestionWithChapter: Identifiable, Hashable {
    let id: Int64
    let chapterId: Int64
    let chapterName: String
    let type: String
    let question: String
    let options: String
    let answer: String
    let analysis: String?
    let isValid: Bool
}

/// 错题列表项：题目信息 + 章节名 + 错误作答（科目错题本用）
struct WrongQuestionItem: Identifiable, Hashable {
    let id: Int64
    let bankId: Int64
    let chapterId: Int64
    let chapterName: String
    let type: String
    let question: String
    let options: String
    let answer: String
    let analysis: String?
    let wrongAnswer: String
    let wrongAt: Int64
    let isValid: Bool
}

extension Date {
    /// 毫秒级时间戳（与 Android System.currentTimeMillis() 对齐）
    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
