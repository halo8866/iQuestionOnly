import Foundation

// MARK: - 备份导出器（Android BackupExporter 的移植）
// 把所有科目/章节/题目导出为可重新导入的 JSON 文件；每道题都带题目级 subject/chapter 字段

enum BackupExporter {

    /// 生成备份 JSON 字符串（格式化、中文不转义）
    static func buildJson(_ store: QuestionStore) async throws -> String {
        let subjects = await store.getAllSubjectsWithCounts()
        var questions: [JsonQuestion] = []
        var chapterCount = 0

        for subject in subjects {
            let chapters = await store.getChaptersBySubject(subject.id)
            chapterCount += chapters.count
            for chapter in chapters {
                for q in await store.getQuestionsByChapter(chapter.id) {
                    let opts = JsonImporter.parseOptions(q.options)
                    questions.append(JsonQuestion(
                        type: q.type,
                        question: q.question,
                        options: opts.isEmpty ? nil : opts,
                        answer: .string(q.answer),
                        analysis: q.analysis,
                        subject: subject.name,
                        chapter: chapter.name,
                        // 有效题不导出字段，无效题导出 false
                        isValid: q.isValid ? nil : false
                    ))
                }
            }
        }

        let bank = JsonQuestionBank(
            name: "题梦 备份题库",
            description: "包含 \(subjects.count) 个科目、\(chapterCount) 个章节、\(questions.count) 道题；" +
                "导入本文件时会按题目级科目/章节自动还原结构，可用于重装软件后恢复",
            questions: questions
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        encoder.keyEncodingStrategy = .useDefaultKeys
        let data = try encoder.encode(bank)
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}
