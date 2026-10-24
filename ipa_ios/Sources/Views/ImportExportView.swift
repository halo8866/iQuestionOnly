import SwiftUI
import UniformTypeIdentifiers

// MARK: - 导入导出页：导入 JSON 题库 / 下载模板 / 导出备份

struct ImportExportView: View {
    @State private var showImporter = false
    @State private var showTemplateExporter = false
    @State private var showBackupExporter = false
    @State private var templateDoc: TextFile?
    @State private var backupDoc: TextFile?
    @State private var backupFileName = "题梦_备份.json"

    @State private var pendingImport: JsonImporter.ImportData?
    @State private var localQuestionCount = 0
    @State private var confirmClearImport = false
    @State private var busy = false
    @State private var result: (ok: Bool, text: String)?
    @State private var toast: String?

    private let store = QuestionStore.shared

    var body: some View {
        Form {
            Section("导入题库") {
                Button {
                    showImporter = true
                } label: {
                    Label("选择 JSON 题库文件导入", systemImage: "square.and.arrow.down")
                }
                .disabled(busy)
                Button {
                    templateDoc = TextFile(text: Self.templateJson)
                    showTemplateExporter = true
                } label: {
                    Label("下载 JSON 题库模板", systemImage: "arrow.down.doc")
                }
                .disabled(busy)
                Text("科目/章节不存在时导入会自动创建；题目级 subject/chapter 可覆盖顶层设置")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Section("导出备份") {
                Button {
                    Task { await startExport() }
                } label: {
                    Label("导出全部题目为 JSON 备份", systemImage: "square.and.arrow.up")
                }
                .disabled(busy)
                Text("备份包含全部科目/章节/题目（含无效题），可用于重装后恢复或在其他设备导入")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            if let r = result {
                Section {
                    Text(r.text)
                        .font(.subheadline)
                        .foregroundColor(r.ok ? Theme.ok : Theme.danger)
                }
            }
            if busy {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            }
        }
        .navigationTitle("导入导出")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.json, .plainText]) { res in
            handleImportResult(res)
        }
        .fileExporter(isPresented: $showTemplateExporter,
                      document: templateDoc,
                      contentType: .json,
                      defaultFilename: "题梦_题库模板.json") { res in
            if case .success = res {
                result = (true, "模板已保存")
                toast = "模板已保存"
            } else if case .failure(let e) = res {
                result = (false, "模板保存失败：\(e.localizedDescription)")
            }
        }
        .fileExporter(isPresented: $showBackupExporter,
                      document: backupDoc,
                      contentType: .json,
                      defaultFilename: backupFileName) { res in
            if case .success = res {
                result = (true, "备份已导出")
                toast = "备份已导出"
            } else if case .failure(let e) = res {
                result = (false, "备份导出失败：\(e.localizedDescription)")
            }
        }
        // 导入方式选择
        .sheet(isPresented: Binding(
            get: { pendingImport != nil && !confirmClearImport },
            set: { if !$0 { pendingImport = nil } }
        )) {
            importChoiceSheet
        }
        .alert("清空后导入", isPresented: $confirmClearImport) {
            Button("确认清空并导入", role: .destructive) {
                if let data = pendingImport { applyImport(data, clearFirst: true) }
                pendingImport = nil
            }
            Button("取消", role: .cancel) { pendingImport = nil }
        } message: {
            Text("本机现有 \(localQuestionCount) 道题将被全部删除，替换为文件中的 \(pendingImport?.questions.count ?? 0) 道题。此操作不可恢复，确定继续？")
        }
        .toast($toast)
    }

    private var importChoiceSheet: some View {
        VStack(spacing: 18) {
            Text("选择导入方式")
                .font(.headline)
                .padding(.top, 24)
            Text("文件共解析出 \(pendingImport?.questions.count ?? 0) 道题")
                .font(.subheadline)
                .foregroundColor(.secondary)
            choiceCard(icon: "plus.square.on.square", title: "合并导入",
                       desc: "保留本机现有题目，把文件中的题目追加进来（同名科目/章节会复用）") {
                if let data = pendingImport { applyImport(data, clearFirst: false) }
                pendingImport = nil
            }
            choiceCard(icon: "trash", title: "清空后导入",
                       desc: "删除本机全部数据后，再导入文件中的题目（不可恢复）",
                       tint: Theme.danger) {
                localQuestionCount = 0
                confirmClearImport = true
                Task {
                    let subjects = await store.getAllSubjectsWithCounts()
                    localQuestionCount = subjects.reduce(0) { $0 + $1.questionCount }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 18)
        .presentationDetents([.medium])
    }

    private func choiceCard(icon: String, title: String, desc: String,
                            tint: Color = Theme.primary,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                    .foregroundColor(tint)
                    .frame(width: 36)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundColor(tint)
                    Text(desc)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
            }
            .padding(14)
            .background(Theme.card)
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }

    // MARK: 导入

    private func handleImportResult(_ res: Result<URL, Error>) {
        switch res {
        case .success(let url):
            Task { await importFromURL(url) }
        case .failure(let e):
            result = (false, "导入失败：\(e.localizedDescription)")
        }
    }

    private func importFromURL(_ url: URL) async {
        busy = true
        result = nil
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        do {
            let json = try String(contentsOf: url, encoding: .utf8)
            let data = try JsonImporter.parse(json)
            let subjects = await store.getAllSubjectsWithCounts()
            localQuestionCount = subjects.reduce(0) { $0 + $1.questionCount }
            busy = false
            pendingImport = data
        } catch {
            busy = false
            result = (false, "导入失败：\(error.localizedDescription)")
        }
    }

    private func applyImport(_ data: JsonImporter.ImportData, clearFirst: Bool) {
        busy = true
        Task {
            do {
                if clearFirst {
                    try await store.clearAllData()
                }
                var resolved: [Question] = []
                for (index, var q) in data.questions.enumerated() {
                    guard let sName = q.subjectName ?? data.subjectName else {
                        throw ImportError("第 \(index + 1) 题缺少科目信息：请在 JSON 顶层或该题中填写 subject 字段。")
                    }
                    guard let cName = q.chapterName ?? data.defaultChapterName else {
                        throw ImportError("第 \(index + 1) 题缺少章节信息：请在 JSON 顶层或该题中填写 chapter 字段。")
                    }
                    let subject: Subject
                    if let existing = await store.findSubjectByName(sName) {
                        subject = existing
                    } else {
                        let id = try await store.insertSubject(Subject(name: sName))
                        subject = Subject(id: id, name: sName)
                    }
                    let chapter: Chapter
                    if let existing = await store.findChapter(subject.id, cName) {
                        chapter = existing
                    } else {
                        let id = try await store.insertChapter(Chapter(subjectId: subject.id, name: cName))
                        chapter = Chapter(id: id, subjectId: subject.id, name: cName)
                    }
                    q.chapterId = chapter.id
                    resolved.append(q)
                }
                _ = try await store.insertBankWithQuestions(data.bank, resolved)
                let msg = clearFirst
                    ? "清空后导入成功，共 \(resolved.count) 道题"
                    : "合并导入成功，共 \(resolved.count) 道题"
                result = (true, msg)
                toast = msg
            } catch {
                result = (false, "导入失败：\(error.localizedDescription)")
            }
            busy = false
        }
    }

    // MARK: 导出

    private func startExport() async {
        let subjects = await store.getAllSubjectsWithCounts()
        if subjects.isEmpty || subjects.allSatisfy({ $0.questionCount == 0 }) {
            toast = "本机没有可导出的题目"
            return
        }
        do {
            let json = try await BackupExporter.buildJson(store)
            let stamp = Self.fileStamp()
            backupFileName = "题梦_备份_\(stamp).json"
            backupDoc = TextFile(text: json)
            showBackupExporter = true
        } catch {
            result = (false, "备份导出失败：\(error.localizedDescription)")
        }
    }

    private static func fileStamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmm"
        return f.string(from: Date())
    }

    // MARK: 模板（与 Android 端模板一致）

    private static let templateJson = """
    {
      "name": "我的题库",
      "description": "题库描述（可选），按下方模板修改后导入即可",
      "subject": "计算机基础",
      "chapter": "第一章 基础知识",
      "questions": [
        {
          "type": "single",
          "question": "【单选题示例】1 + 1 = ?",
          "options": ["A. 1", "B. 2", "C. 3", "D. 4"],
          "answer": "B",
          "analysis": "解析（可选）：1 + 1 = 2"
        },
        {
          "type": "multiple",
          "question": "【多选题示例】以下哪些是偶数？",
          "options": ["A. 2", "B. 3", "C. 4", "D. 5"],
          "answer": ["A", "C"],
          "analysis": "解析（可选）：2 和 4 是偶数；答案也可写成逗号分隔的字符串 \\"A,C\\""
        },
        {
          "type": "judge",
          "question": "【判断题示例】地球是太阳系的行星。",
          "answer": "正确",
          "analysis": "解析（可选）：判断题无需 options；answer 可填 正确/错误 或 true/false"
        },
        {
          "type": "essay",
          "question": "【解答题示例】请简述面向对象的三大基本特征。",
          "answer": "封装：将数据和操作封装在类中，对外隐藏实现细节；\\n继承：子类复用父类的属性和方法，并可扩展；\\n多态：同一接口在不同对象上表现出不同行为（如方法重写、重载）。",
          "analysis": "解析（可选）：解答题无需 options，answer 填参考答案；答题时用户输入自己的解答，提交后对照参考答案自评得分。"
        },
        {
          "type": "single",
          "question": "【跨章节示例】本题将导入到「第二章 数据结构」章节（题目级 chapter 覆盖顶层 chapter）。",
          "options": ["A. 先进先出", "B. 先进后出", "C. 随机存取", "D. 按优先级存取"],
          "answer": "B",
          "analysis": "栈的特点是先进后出（FILO）。",
          "chapter": "第二章 数据结构"
        }
      ]
    }
    """
}
