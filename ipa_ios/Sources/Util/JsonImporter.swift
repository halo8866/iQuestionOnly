import Foundation

// MARK: - JSON 题库数据结构（与 Android 端格式互通）

/// JSON 题库数据结构（用于导入解析）
/// subject/chapter: 顶层默认科目/章节；科目或章节不存在时导入会自动创建
struct JsonQuestionBank: Codable {
    var name: String?
    var description: String?
    var subject: String?
    var chapter: String?
    // 可选：与 Android Gson 行为一致，缺失视为空列表
    var questions: [JsonQuestion]?
}

/// JSON 题目数据结构
/// type: single | multiple | judge | essay
/// answer: 可以是字符串（单选/判断）或字符串数组（多选）
struct JsonQuestion: Codable {
    var type: String?
    var question: String?
    var options: [String]?
    var answer: AnswerValue?
    var analysis: String?
    var subject: String?
    var chapter: String?
    var isValid: Bool?
}

/// answer 字段：字符串或字符串数组
enum AnswerValue: Codable {
    case string(String)
    case list([String])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([String].self) { self = .list(a); return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "answer 既不是字符串也不是数组")
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .list(let a): try c.encode(a)
        }
    }

    var asList: [String] {
        switch self {
        case .string(let s): return [s]
        case .list(let a): return a
        }
    }
}

// MARK: - JSON 题库导入器（Android JsonImporter 的完整移植）

enum JsonImporter {

    /// 导入解析结果
    struct ImportData {
        let bank: QuestionBank
        /// 题目列表（chapterId 未设置，chapterName/subjectName 携带每题目标科目/章节名）
        let questions: [Question]
        let subjectName: String?
        let defaultChapterName: String?
    }

    static func parse(_ json: String) throws -> ImportData {
        guard let data = json.data(using: .utf8) else {
            throw ImportError("无法解析 JSON 文件")
        }
        let bank: JsonQuestionBank
        do {
            bank = try JSONDecoder().decode(JsonQuestionBank.self, from: data)
        } catch {
            throw ImportError("JSON 格式错误：\(error.localizedDescription)")
        }

        guard let bankName = bank.name?.trimmingCharacters(in: .whitespacesAndNewlines),
              !bankName.isEmpty else {
            throw ImportError("题库名称(name)不能为空")
        }

        let subjectName = bank.subject?.trimmingCharacters(in: .whitespacesAndNewlines)
        let defaultChapterName = bank.chapter?.trimmingCharacters(in: .whitespacesAndNewlines)
        let subjectFinal = (subjectName?.isEmpty == false) ? subjectName : nil
        let chapterFinal = (defaultChapterName?.isEmpty == false) ? defaultChapterName : nil

        let jsonQuestions = bank.questions ?? []
        guard !jsonQuestions.isEmpty else {
            throw ImportError("题库中没有题目")
        }

        let questionBank = QuestionBank(name: bankName, description: bank.description,
                                        questionCount: jsonQuestions.count)

        let questions = try jsonQuestions.enumerated().map { index, jsonQ in
            try convertToQuestion(jsonQ, index: index)
        }

        return ImportData(bank: questionBank, questions: questions,
                          subjectName: subjectFinal, defaultChapterName: chapterFinal)
    }

    private static func convertToQuestion(_ jsonQ: JsonQuestion, index: Int) throws -> Question {
        let type = try normalizeType(jsonQ.type)
        guard let questionText = jsonQ.question?.trimmingCharacters(in: .whitespacesAndNewlines),
              !questionText.isEmpty else {
            throw ImportError("第 \(index + 1) 题的题目内容为空")
        }

        let options = jsonQ.options ?? []
        let answer = try normalizeAnswer(jsonQ.answer, type: type)

        // 判断题如果没有提供选项，自动生成；解答题不需要选项
        let finalOptions: [String]
        switch type {
        case "judge": finalOptions = options.isEmpty ? ["正确", "错误"] : options
        case "essay": finalOptions = []
        default: finalOptions = options
        }

        if type != "judge" && type != "essay" && finalOptions.isEmpty {
            throw ImportError("第 \(index + 1) 题缺少选项")
        }

        return Question(
            bankId: 0, // 插入后由调用方设置
            type: type,
            question: questionText,
            options: jsonArrayString(finalOptions),
            answer: answer,
            analysis: jsonQ.analysis,
            isValid: jsonQ.isValid ?? true,
            chapterName: trimToNil(jsonQ.chapter),
            subjectName: trimToNil(jsonQ.subject)
        )
    }

    /// 标准化题型
    static func normalizeType(_ type: String?) throws -> String {
        let t = (type ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch t {
        case "single", "单选题", "单选", "choice": return "single"
        case "multiple", "多选题", "多选", "multi": return "multiple"
        case "judge", "判断题", "判断", "truefalse", "tf", "boolean": return "judge"
        case "essay", "解答题", "简答题", "简答", "问答", "问答题",
             "shortanswer", "short_answer", "short-answer", "text", "qa", "q&a", "subjective":
            return "essay"
        case "": throw ImportError("题型(type)不能为空")
        default: throw ImportError("不支持的题型：\(t)")
        }
    }

    /// 标准化答案
    /// 单选：返回单个字母如 "A"；多选：逗号分隔如 "A,B"；判断："正确"/"错误"；解答：参考答案文本
    static func normalizeAnswer(_ answer: AnswerValue?, type: String) throws -> String {
        guard let answer else { throw ImportError("答案不能为空") }

        switch type {
        case "single":
            let s = (answer.asList.first ?? "").trimmingCharacters(in: .whitespaces).uppercased()
            if s.isEmpty { throw ImportError("单选题答案为空") }
            return s
        case "multiple":
            var list: [String]
            switch answer {
            case .list(let arr): list = arr
            case .string(let s): list = s.split(whereSeparator: { ",， ".contains($0) }).map(String.init)
            }
            list = list.map { $0.trimmingCharacters(in: .whitespaces).uppercased() }.filter { !$0.isEmpty }
            if list.isEmpty { throw ImportError("多选题答案为空") }
            return list.joined(separator: ",")
        case "judge":
            let s = answer.asList.first ?? ""
            switch s.trimmingCharacters(in: .whitespaces).lowercased() {
            case "正确", "对", "true", "t", "yes", "y", "是", "a": return "正确"
            case "错误", "错", "false", "f", "no", "n", "否", "b": return "错误"
            default: return s
            }
        case "essay":
            // 解答题：answer 为参考答案文本；若误传数组则换行拼接
            let s: String
            switch answer {
            case .list(let arr): s = arr.joined(separator: "\n")
            case .string(let str): s = str
            }
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { throw ImportError("解答题参考答案为空") }
            return trimmed
        default:
            throw ImportError("未知题型")
        }
    }

    /// 判断答案是否正确（仅适用于客观题；解答题为主观题，需用户自评）
    static func isAnswerCorrect(userAnswer: [String], question: Question) -> Bool {
        if question.type == "essay" { return false }
        if userAnswer.isEmpty { return false }
        let correctOptions = getCorrectOptions(question)
        if correctOptions.isEmpty { return false }
        return Set(userAnswer) == Set(correctOptions)
    }

    /// 获取正确选项的文本列表；解答题返回参考答案文本（单元素列表）
    static func getCorrectOptions(_ question: Question) -> [String] {
        let options = parseOptions(question.options)
        let answer = question.answer

        switch question.type {
        case "single":
            let letter = answer.uppercased()
            return options.filter { $0.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix(letter) }
        case "multiple":
            let letters = answer.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
            return options.filter { opt in
                letters.contains { opt.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix($0) }
            }
        case "judge": return [answer]
        case "essay": return [answer]
        default: return []
        }
    }

    /// 解析选项 JSON 字符串为列表
    static func parseOptions(_ optionsJson: String) -> [String] {
        let trimmed = optionsJson.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [String] else { return [] }
        return arr
    }

    /// 获取答案显示文本
    static func getAnswerDisplay(_ question: Question) -> String {
        getCorrectOptions(question).joined(separator: "；")
    }

    // MARK: 内部工具

    static func trimToNil(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
        return t
    }

    /// 序列化为 JSON 数组字符串（与 Android JSONArray.toString() 一致）
    static func jsonArrayString(_ list: [String]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: list),
              let s = String(data: data, encoding: .utf8) else { return "[]" }
        return s
    }
}

/// 导入错误（对应 Android IllegalArgumentException）
struct ImportError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
