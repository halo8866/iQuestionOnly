import Foundation

// MARK: - 考试草稿（暂存考试，Android ExamDraftStore 的移植）

struct ExamDraft: Codable {
    var subjectId: Int64
    var subjectName: String
    var chapterIds: [Int64]
    var countSingle: Int
    var countMultiple: Int
    var countJudge: Int
    var countEssay: Int
    var durationMinutes: Int
    var remainingMillis: Int64
    var questionIds: [Int64]
    var currentIndex: Int
    var userSelections: [Int: [String]]
    var savedAt: Int64
}

enum ExamDraftStore {
    private static let key = "exam_draft"

    /// 保存一份草稿（覆盖旧草稿）
    static func save(_ draft: ExamDraft) {
        if let data = try? JSONEncoder().encode(draft) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    /// 读取草稿
    static func load() -> ExamDraft? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(ExamDraft.self, from: data)
    }

    /// 清除草稿
    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
