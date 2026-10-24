import SwiftUI

// MARK: - 科目题目管理页：筛选、编辑、删除、批量管理

struct QuestionManageView: View {
    let subjectId: Int64
    let subjectName: String

    @State private var questions: [QuestionWithChapter] = []
    @State private var chapters: [Chapter] = []
    @State private var filterChapterId: Int64?
    @State private var filterValid = 0 // 0 全部 1 仅有效 2 仅无效
    @State private var selectionMode = false
    @State private var selectedIds = Set<Int64>()
    @State private var pendingDelete: QuestionWithChapter?
    @State private var confirmBatchDelete = false
    @State private var editQuestionId: Int64?
    @State private var toast: String?

    private let store = QuestionStore.shared

    var body: some View {
        Group {
            if questions.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 44))
                        .foregroundColor(.secondary)
                    Text("该筛选条件下没有题目").foregroundColor(.secondary)
                }
            } else {
                List {
                    ForEach(questions) { q in
                        rowButton(q)
                    }
                }
            }
        }
        .navigationTitle(selectionMode ? "已选 \(selectedIds.count) 项" : "题目管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if selectionMode {
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("全选") { selectAll() }
                    Button("设为有效") { Task { await applyBatchValid(true) } }
                    Button("设为无效") { Task { await applyBatchValid(false) } }
                    Button("删除", role: .destructive) {
                        if selectedIds.isEmpty { toast = "请先选择题目" } else { confirmBatchDelete = true }
                    }
                    Spacer()
                    Button("取消") { exitSelection() }
                }
            } else {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { selectionMode = true } label: { Label("批量管理", systemImage: "checklist") }
                        Menu("按章节筛选") {
                            Button("全部章节") { filterChapterId = nil }
                            ForEach(chapters) { c in
                                Button(c.name) { filterChapterId = c.id }
                            }
                        }
                        Menu("按状态筛选") {
                            Button("全部") { filterValid = 0 }
                            Button("仅有效") { filterValid = 1 }
                            Button("仅无效") { filterValid = 2 }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { editQuestionId != nil },
            set: { if !$0 { editQuestionId = nil } }
        )) {
            if let qid = editQuestionId {
                EditQuestionView(questionId: qid, subjectId: subjectId)
            }
        }
        .confirmationDialog("删除题目", isPresented: Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        ), titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let q = pendingDelete {
                    Task {
                        try? await store.deleteQuestionById(q.id)
                        pendingDelete = nil
                        toast = "修改成功"
                        await load()
                    }
                }
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("确定删除该题目？删除后无法恢复。")
        }
        .confirmationDialog("批量删除", isPresented: $confirmBatchDelete, titleVisibility: .visible) {
            Button("删除 \(selectedIds.count) 题", role: .destructive) {
                Task {
                    try? await store.deleteQuestionsByIds(Array(selectedIds))
                    toast = "已删除 \(selectedIds.count) 道题"
                    exitSelection()
                    await load()
                }
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text("确定删除选中的 \(selectedIds.count) 道题？删除后无法恢复。")
        }
        .toast($toast)
        .task { await load() }
    }

    private func rowButton(_ q: QuestionWithChapter) -> some View {
        Button {
            if selectionMode { toggleSelect(q.id) } else { editQuestionId = q.id }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                if selectionMode {
                    Image(systemName: selectedIds.contains(q.id) ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(selectedIds.contains(q.id) ? Theme.primary : .secondary)
                }
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(Theme.typeName(q.type))
                            .font(.caption2).bold()
                            .foregroundColor(Theme.primary)
                        Text(q.chapterName)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        if !q.isValid {
                            Text("无效")
                                .font(.caption2).bold()
                                .foregroundColor(.white)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(Capsule().fill(Color.gray))
                        }
                    }
                    Text(q.question)
                        .font(.subheadline)
                        .foregroundColor(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .opacity(q.isValid ? 1.0 : 0.55)
            .contentShape(Rectangle())
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !selectionMode {
                Button(role: .destructive) { pendingDelete = q } label: { Label("删除", systemImage: "trash") }
                Button { Task { try? await store.setQuestionValid(q.id, !q.isValid); await load() } } label: {
                    Label(q.isValid ? "设为无效" : "设为有效", systemImage: q.isValid ? "eye.slash" : "eye")
                }
                .tint(.orange)
            }
        }
        .contextMenu {
            if !selectionMode {
                Button { selectionMode = true; selectedIds.insert(q.id) } label: {
                    Label("批量管理", systemImage: "checklist")
                }
            }
        }
    }

    private func load() async {
        chapters = await store.getChaptersBySubject(subjectId)
        var list: [QuestionWithChapter]
        if let cid = filterChapterId {
            list = await store.getSubjectQuestionsByChapter(subjectId, cid)
        } else {
            list = await store.getSubjectQuestions(subjectId)
        }
        switch filterValid {
        case 1: list = list.filter { $0.isValid }
        case 2: list = list.filter { !$0.isValid }
        default: break
        }
        list.sort { ($0.isValid ? 0 : 1) < ($1.isValid ? 0 : 1) }
        questions = list
        selectedIds.formIntersection(Set(questions.map { $0.id }))
    }

    private func toggleSelect(_ id: Int64) {
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
    }

    private func selectAll() {
        if selectedIds.count == questions.count {
            selectedIds.removeAll()
        } else {
            selectedIds = Set(questions.map { $0.id })
        }
    }

    private func exitSelection() {
        selectionMode = false
        selectedIds.removeAll()
    }

    private func applyBatchValid(_ valid: Bool) async {
        if selectedIds.isEmpty {
            toast = "请先选择题目"
            return
        }
        try? await store.setQuestionsValid(Array(selectedIds), valid)
        toast = valid ? "已将 \(selectedIds.count) 道题设为有效" : "已将 \(selectedIds.count) 道题设为无效"
        exitSelection()
        await load()
    }
}

// MARK: - 题目编辑页

struct EditQuestionView: View {
    let questionId: Int64
    let subjectId: Int64

    @Environment(\.dismiss) private var dismiss
    @State private var chapters: [Chapter] = []
    @State private var chapterIndex = 0
    @State private var type = "single"
    @State private var questionText = ""
    @State private var analysis = ""
    @State private var isValid = true
    @State private var essayAnswer = ""
    @State private var rows: [OptionRow] = []
    @State private var savedOptionState: [OptionRow] = []
    @State private var original: Question?
    @State private var toast: String?

    private let store = QuestionStore.shared
    private let typeCodes = ["single", "multiple", "judge", "essay"]
    private let typeNames = ["单选题", "多选题", "判断题", "解答题"]

    struct OptionRow: Identifiable {
        let id = UUID()
        var text: String
        var checked: Bool
    }

    var body: some View {
        Form {
            Picker("所属章节", selection: $chapterIndex) {
                ForEach(0..<chapters.count, id: \.self) { i in
                    Text(chapters[i].name).tag(i)
                }
            }
            Picker("题型", selection: $type) {
                ForEach(0..<typeCodes.count, id: \.self) { i in
                    Text(typeNames[i]).tag(typeCodes[i])
                }
            }
            Section("题目内容") {
                TextEditor(text: $questionText)
                    .frame(minHeight: 80)
            }
            if type == "essay" {
                Section("参考答案") {
                    TextEditor(text: $essayAnswer)
                        .frame(minHeight: 80)
                }
            } else {
                Section("选项（勾选为正确答案）") {
                    ForEach(0..<rows.count, id: \.self) { i in
                        optionRowEditor(i)
                    }
                    .onDelete { offsets in
                        guard rows.count - offsets.count >= 2 else {
                            toast = "至少需要两个选项"
                            return
                        }
                        rows.remove(atOffsets: offsets)
                    }
                    if type != "judge" {
                        Button { rows.append(OptionRow(text: "", checked: false)) } label: {
                            Label("添加选项", systemImage: "plus")
                        }
                    }
                }
            }
            Section("解析（可选）") {
                TextEditor(text: $analysis)
                    .frame(minHeight: 60)
            }
            Section {
                Toggle("有效（参与练习/考试）", isOn: $isValid)
                Button("保存修改") { Task { await save() } }
                    .frame(maxWidth: .infinity)
                    .bold()
            }
        }
        .navigationTitle("编辑题目")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: type) { newType in
            guard original != nil else { return }
            if !rows.isEmpty { savedOptionState = rows }
            rebuildOptions(type: newType, fromStored: false)
        }
        .toast($toast)
        .task { await load() }
    }

    private func optionRowEditor(_ i: Int) -> some View {
        HStack(spacing: 10) {
            Button {
                toggleCheck(i)
            } label: {
                Image(systemName: rows[i].checked ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(rows[i].checked ? Theme.ok : .secondary)
            }
            .buttonStyle(.plain)
            TextField("选项内容", text: Binding(get: { rows[i].text }, set: { rows[i].text = $0 }))
            if type != "judge" && rows.count > 2 {
                Button {
                    rows.remove(at: i)
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .foregroundColor(.red)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func load() async {
        chapters = await store.getChaptersBySubject(subjectId)
        guard let q = await store.getQuestionById(questionId), !chapters.isEmpty else {
            toast = "题目不存在"
            try? await Task.sleep(nanoseconds: 600_000_000)
            dismiss()
            return
        }
        original = q
        questionText = q.question
        analysis = q.analysis ?? ""
        isValid = q.isValid
        chapterIndex = max(chapters.firstIndex { $0.id == q.chapterId } ?? 0, 0)
        type = q.type
        rebuildOptions(type: q.type, fromStored: true)
    }

    /// 按题型重建选项区（与 Android EditQuestionActivity 行为一致）
    private func rebuildOptions(type newType: String, fromStored: Bool) {
        guard let q = original else { return }
        if newType == "essay" {
            if fromStored { essayAnswer = q.answer }
            return
        }
        if newType == "judge" {
            let texts: [String]
            var checkedIdx = -1
            if fromStored {
                var t = JsonImporter.parseOptions(q.options)
                if t.isEmpty { t = ["正确", "错误"] }
                texts = Array(t.prefix(2))
                checkedIdx = texts.firstIndex { $0 == q.answer } ?? -1
            } else {
                let s = savedOptionState
                texts = s.count >= 2 ? [s[0].text, s[1].text] : ["正确", "错误"]
                checkedIdx = s.prefix(2).firstIndex { $0.checked } ?? -1
            }
            rows = texts.enumerated().map { OptionRow(text: $1, checked: $0 == checkedIdx) }
            return
        }
        // single / multiple
        var state: [OptionRow]
        if fromStored {
            let opts = JsonImporter.parseOptions(q.options)
            let ans = q.answer.uppercased()
            state = opts.map { text in
                let trimmed = text.trimmingCharacters(in: .whitespaces).uppercased()
                let checked: Bool
                switch q.type {
                case "single": checked = trimmed.hasPrefix(ans)
                case "multiple": checked = ans.split(separator: ",").contains { trimmed.hasPrefix($0.trimmingCharacters(in: .whitespaces)) }
                default: checked = false
                }
                return OptionRow(text: text, checked: checked)
            }
            if state.isEmpty { state = (0..<4).map { _ in OptionRow(text: "", checked: false) } }
        } else {
            state = savedOptionState.isEmpty ? (0..<4).map { _ in OptionRow(text: "", checked: false) } : savedOptionState
        }
        if newType == "single", let first = state.firstIndex(where: { $0.checked }) {
            for i in state.indices where i != first { state[i].checked = false }
        }
        while state.count < 2 { state.append(OptionRow(text: "", checked: false)) }
        rows = state
    }

    private func toggleCheck(_ i: Int) {
        let exclusive = type == "single" || type == "judge"
        let newVal = !rows[i].checked
        if newVal && exclusive {
            for j in rows.indices { rows[j].checked = (j == i) }
        } else {
            rows[i].checked = newVal
        }
    }

    private func save() async {
        guard let q = original else { return }
        let qt = questionText.trimmingCharacters(in: .whitespacesAndNewlines)
        if qt.isEmpty { toast = "题目内容不能为空"; return }

        let analysisTrim = analysis.trimmingCharacters(in: .whitespacesAndNewlines)
        let chapterId = chapters.indices.contains(chapterIndex) ? chapters[chapterIndex].id : q.chapterId

        var optionsJson = "[]"
        var answer = ""
        switch type {
        case "essay":
            let ref = essayAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
            if ref.isEmpty { toast = "解答题参考答案为空"; return }
            answer = ref
        case "judge":
            if rows.contains(where: { $0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
                toast = "选项内容不能为空"; return
            }
            let checked = rows.filter { $0.checked }
            if checked.count != 1 { toast = "判断题必须且只能勾选一个正确答案"; return }
            optionsJson = JsonImporter.jsonArrayString(rows.map { $0.text.trimmingCharacters(in: .whitespaces) })
            answer = checked[0].text.trimmingCharacters(in: .whitespaces)
        default:
            if rows.count < 2 { toast = "至少需要两个选项"; return }
            if rows.contains(where: { $0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
                toast = "选项内容不能为空"; return
            }
            let idxs = rows.indices.filter { rows[$0].checked }
            let ok = type == "single" ? idxs.count == 1 : !idxs.isEmpty
            if !ok {
                toast = type == "single" ? "单选题必须且只能勾选一个正确答案" : "多选题至少勾选一个正确答案"
                return
            }
            optionsJson = JsonImporter.jsonArrayString(rows.map { $0.text.trimmingCharacters(in: .whitespaces) })
            answer = idxs.map { String(Character(UnicodeScalar(65 + $0)!)) }.joined(separator: ",")
        }

        var updated = q
        updated.chapterId = chapterId
        updated.type = type
        updated.question = qt
        updated.options = optionsJson
        updated.answer = answer
        updated.analysis = analysisTrim.isEmpty ? nil : analysisTrim
        updated.isValid = isValid

        do {
            try await store.updateQuestion(updated)
            toast = "修改成功"
            try? await Task.sleep(nanoseconds: 400_000_000)
            dismiss()
        } catch {
            toast = "保存失败：\(error.localizedDescription)"
        }
    }
}
