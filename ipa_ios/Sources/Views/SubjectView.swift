import SwiftUI

// MARK: - 科目详情页：章节列表 + 章节练习 + 科目考试

struct SubjectView: View {
    let subjectId: Int64
    let subjectName: String

    @State private var chapters: [ChapterWithCounts] = []
    @State private var showAdd = false
    @State private var newName = ""
    @State private var pendingDelete: ChapterWithCounts?
    @State private var modePickChapter: ChapterWithCounts?
    @State private var practiceRoute: PracticeSource?

    // 科目考试流程
    @State private var pendingDraft: ExamDraft?
    @State private var showChapterPicker = false
    @State private var showExamSettings = false
    @State private var examChapterIds: [Int64]?
    @State private var toast: String?

    var body: some View {
        Group {
            if chapters.isEmpty {
                emptyView
            } else {
                chapterList
            }
        }
        .navigationTitle(subjectName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button { showAdd = true } label: { Label("添加章节", systemImage: "plus") }
                    Button { Task { await checkDraftAndStartExam() } } label: {
                        Label("科目考试", systemImage: "timer")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { practiceRoute != nil },
            set: { if !$0 { practiceRoute = nil } }
        )) {
            if let r = practiceRoute { PracticeView(source: r) }
        }
        .alert("添加章节", isPresented: $showAdd) {
            TextField("输入章节名称，如：第一章", text: $newName)
            Button("确定") { Task { await addChapter() } }
            Button("取消", role: .cancel) { }
        }
        .confirmationDialog(
            modePickChapter?.name ?? "",
            isPresented: Binding(get: { modePickChapter != nil },
                                 set: { if !$0 { modePickChapter = nil } }),
            titleVisibility: .visible
        ) {
            Button("顺序练习") { startPractice(random: false) }
            Button("乱序练习") { startPractice(random: true) }
            Button("取消", role: .cancel) { modePickChapter = nil }
        } message: {
            Text("选择练习方式")
        }
        .confirmationDialog(
            pendingDelete?.name ?? "",
            isPresented: Binding(get: { pendingDelete != nil },
                                 set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let c = pendingDelete {
                    Task {
                        try? await QuestionStore.shared.deleteChapter(c.id)
                        pendingDelete = nil
                        await load()
                    }
                }
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("删除章节将同时删除其下所有题目，确定删除？")
        }
        // 恢复暂存考试
        .alert("发现暂存考试", isPresented: Binding(
            get: { pendingDraft != nil },
            set: { if !$0 { pendingDraft = nil } }
        )) {
            Button("恢复考试") {
                if let d = pendingDraft {
                    practiceRoute = .resumeExam(subjectId: subjectId,
                                                subjectName: d.subjectName,
                                                durationMinutes: d.durationMinutes)
                }
                pendingDraft = nil
            }
            Button("新开考试") {
                ExamDraftStore.clear()
                pendingDraft = nil
                openChapterPicker()
            }
            Button("取消", role: .cancel) { pendingDraft = nil }
        } message: {
            if let d = pendingDraft {
                Text(draftMessage(d))
            }
        }
        .sheet(isPresented: $showChapterPicker) {
            ExamChapterPickerSheet(chapters: chapters) { selected in
                showChapterPicker = false
                examChapterIds = selected
                showExamSettings = true
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showExamSettings) {
            ExamSettingsSheet { config in
                showExamSettings = false
                var c = config
                c.subjectId = subjectId
                c.subjectName = subjectName
                c.chapterIds = examChapterIds
                practiceRoute = .exam(c)
            }
            .presentationDetents([.large])
        }
        .toast($toast)
        .task { await load() }
    }

    private var emptyView: some View {
        VStack(spacing: 12) {
            Image(systemName: "folder")
                .font(.system(size: 44))
                .foregroundColor(.secondary)
            Text("暂无章节\n点击右上角 + 添加")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
        }
    }

    private var chapterList: some View {
        List {
            ForEach(chapters) { c in
                Button { tapChapter(c) } label: { chapterRow(c) }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { pendingDelete = c } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }
    }

    private func chapterRow(_ c: ChapterWithCounts) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(c.name).font(.headline).foregroundColor(.primary)
                Text("\(c.questionCount) 题中已做 \(c.doneCount) 题")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }

    private func draftMessage(_ d: ExamDraft) -> String {
        let total = d.questionIds.count
        let done = d.userSelections.values.filter { !$0.isEmpty }.count
        var msg = "《\(d.subjectName)》共 \(total) 题，已作答 \(done) 题"
        if d.durationMinutes > 0 { msg += "，剩余时长 \(d.durationMinutes) 分钟" }
        return msg
    }

    private func load() async {
        chapters = await QuestionStore.shared.getChaptersWithCounts(subjectId)
    }

    private func addChapter() async {
        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            toast = "名称不能为空"
            return
        }
        if let _ = await QuestionStore.shared.findChapter(subjectId, name) {
            toast = "添加失败：章节已存在"
            return
        }
        do {
            _ = try await QuestionStore.shared.insertChapter(Chapter(subjectId: subjectId, name: name))
            newName = ""
            await load()
        } catch {
            toast = "添加失败：\(error.localizedDescription)"
        }
    }

    private func tapChapter(_ c: ChapterWithCounts) {
        if c.questionCount == 0 {
            toast = "该章节没有题目"
            return
        }
        modePickChapter = c
    }

    private func startPractice(random: Bool) {
        guard let c = modePickChapter else { return }
        modePickChapter = nil
        practiceRoute = .chapter(id: c.id, name: c.name, random: random)
    }

    private func checkDraftAndStartExam() async {
        if let d = ExamDraftStore.load(), d.subjectId == subjectId {
            pendingDraft = d
        } else {
            openChapterPicker()
        }
    }

    private func openChapterPicker() {
        if chapters.isEmpty {
            examChapterIds = nil
            showExamSettings = true
        } else {
            showChapterPicker = true
        }
    }
}

// MARK: - 科目考试第一步：多选考试章节

struct ExamChapterPickerSheet: View {
    let chapters: [ChapterWithCounts]
    let onNext: ([Int64]) -> Void

    @State private var selected: Set<Int64> = []
    @State private var toast: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(chapters) { c in
                    Button {
                        if selected.contains(c.id) {
                            selected.remove(c.id)
                        } else {
                            selected.insert(c.id)
                        }
                    } label: {
                        HStack {
                            Image(systemName: selected.contains(c.id) ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(selected.contains(c.id) ? Theme.primary : .secondary)
                            Text("\(c.name)（\(c.questionCount) 题）")
                                .foregroundColor(.primary)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle("选择考试章节")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") { onNext([]) }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("下一步") {
                        if selected.isEmpty {
                            toast = "请至少选择一个章节"
                        } else {
                            onNext(chapters.filter { selected.contains($0.id) }.map { $0.id })
                        }
                    }
                    .bold()
                }
            }
            .onAppear { selected = Set(chapters.map { $0.id }) }
            .toast($toast)
        }
    }
}

// MARK: - 科目考试第二步：题型数量 + 时长设置

struct ExamSettingsSheet: View {
    let onStart: (ExamConfig) -> Void

    @State private var single = "5"
    @State private var multiple = "3"
    @State private var judge = "3"
    @State private var essay = "2"
    @State private var durationIndex = 2
    @State private var customMinutes = ""
    @State private var showCustom = false
    @State private var toast: String?

    // 0 不限时 / 30 / 60 / 90 / 120 / 180 / -1 自定义
    private let durations = [0, 30, 60, 90, 120, 180, -1]
    private let labels = ["不限时", "30 分钟", "60 分钟", "90 分钟", "120 分钟", "180 分钟", "自定义"]

    var body: some View {
        NavigationStack {
            Form {
                Section("考试时长") {
                    Picker("时长", selection: $durationIndex) {
                        ForEach(0..<labels.count, id: \.self) { i in
                            Text(labels[i]).tag(i)
                        }
                    }
                    if showCustom {
                        TextField("输入分钟数（1~9999）", text: $customMinutes)
                            .keyboardType(.numberPad)
                    }
                }
                Section("各题型数量") {
                    countField("单选题", $single)
                    countField("多选题", $multiple)
                    countField("判断题", $judge)
                    countField("解答题", $essay)
                }
                Section {
                    Button("开始考试") { startExam() }
                        .frame(maxWidth: .infinity)
                        .bold()
                }
            }
            .navigationTitle("科目考试设置")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: durationIndex) { i in
                if durations[i] == -1 {
                    showCustom = true
                    customMinutes = ""
                } else {
                    showCustom = false
                }
            }
            .toast($toast)
        }
        .interactiveDismissDisabled(false)
    }

    private func countField(_ title: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 80)
                .textFieldStyle(.roundedBorder)
        }
    }

    private func startExam() {
        let s = Int(single) ?? 0
        let m = Int(multiple) ?? 0
        let j = Int(judge) ?? 0
        let e = Int(essay) ?? 0
        if s + m + j + e == 0 {
            toast = "题目总数量不能为 0"
            return
        }
        var minutes = durations[durationIndex]
        if minutes == -1 {
            let custom = Int(customMinutes) ?? 0
            if custom < 1 || custom > 9999 {
                toast = "请输入 1~9999 之间的分钟数"
                return
            }
            minutes = custom
        }
        // ExamConfig 的 subjectId/name 由调用方（SubjectView）不感知——
        // 这里通过 ExamConfig 占位，SubjectView 在 push 前校正
        let config = ExamConfig(subjectId: 0, subjectName: "", chapterIds: nil,
                                single: s, multiple: m, judge: j, essay: e,
                                durationMinutes: minutes)
        onStart(config)
    }
}
