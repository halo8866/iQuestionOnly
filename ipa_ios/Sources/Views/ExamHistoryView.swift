import SwiftUI

// MARK: - 考试管理：历次考试记录（点击查看 / 批量管理 / 物理删除）

struct ExamHistoryView: View {
    @State private var records: [ExamRecord] = []
    @State private var selectionMode = false
    @State private var selectedIds = Set<Int64>()
    @State private var confirmBatchDelete = false
    @State private var showDetail = false
    @State private var showCleared = false
    @State private var detailRecord: ExamRecord?
    @State private var toast: String?

    private let store = QuestionStore.shared

    var body: some View {
        Group {
            if records.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "doc.text")
                        .font(.system(size: 44))
                        .foregroundColor(.secondary)
                    Text("暂无考试记录").foregroundColor(.secondary)
                }
            } else {
                List {
                    ForEach(records) { r in
                        rowButton(r)
                    }
                }
            }
        }
        .navigationTitle(selectionMode ? "已选 \(selectedIds.count) 场" : "考试管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if selectionMode {
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("全选") {
                        if selectedIds.count == records.count { selectedIds.removeAll() }
                        else { selectedIds = Set(records.map { $0.id }) }
                    }
                    Button("设为有效") { Task { await applyBatchValid(true) } }
                    Button("设为无效") { Task { await applyBatchValid(false) } }
                    Button("删除", role: .destructive) {
                        if selectedIds.isEmpty { toast = "请先选择记录" } else { confirmBatchDelete = true }
                    }
                    Spacer()
                    Button("取消") { exitSelection() }
                }
            } else {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { selectionMode = true } label: { Label("批量管理", systemImage: "checklist") }
                        Button { showCleared = true } label: {
                            Label("已清空的历史", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { showDetail && detailRecord != nil },
            set: { if !$0 { showDetail = false; detailRecord = nil } }
        )) {
            if let r = detailRecord {
                ExamHistoryDetailView(recordId: r.id, recordTitle: r.title)
            }
        }
        .navigationDestination(isPresented: $showCleared) {
            ClearedExamHistoryView()
        }
        .confirmationDialog("批量删除", isPresented: $confirmBatchDelete, titleVisibility: .visible) {
            Button("删除 \(selectedIds.count) 场", role: .destructive) {
                Task {
                    try? await store.deleteExamRecordsPhysically(Array(selectedIds))
                    toast = "已删除 \(selectedIds.count) 场考试记录"
                    exitSelection()
                    await load()
                }
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text("将连同作答快照一并物理删除，不可恢复。确定删除？")
        }
        .toast($toast)
        .task { await load() }
    }

    private func rowButton(_ r: ExamRecord) -> some View {
        let rate = r.totalCount > 0 ? Double(r.correctCount) * 100.0 / Double(r.totalCount) : 0.0
        return Button {
            if selectionMode {
                toggleSelect(r.id)
            } else {
                detailRecord = r
                showDetail = true
            }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                if selectionMode {
                    Image(systemName: selectedIds.contains(r.id) ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(selectedIds.contains(r.id) ? Theme.primary : .secondary)
                }
                VStack(alignment: .leading, spacing: 5) {
                    Text(Fmt.dateTimeText(r.createdAt))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text(r.title)
                        .font(.subheadline).bold()
                        .foregroundColor(.primary)
                    Text("得分 \(Fmt.rateText(r.correctCount, r.totalCount))")
                        .font(.caption).bold()
                        .foregroundColor(Theme.scoreColor(rate))
                }
                Spacer()
                if !r.isValid {
                    Text("无效")
                        .font(.caption2).bold()
                        .foregroundColor(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Capsule().fill(Color.gray))
                }
            }
            .opacity(r.isValid ? 1.0 : 0.55)
            .contentShape(Rectangle())
        }
        .contextMenu {
            if !selectionMode {
                Button { selectionMode = true; selectedIds.insert(r.id) } label: {
                    Label("批量管理", systemImage: "checklist")
                }
            }
        }
    }

    private func load() async {
        let list = await store.getExamRecords()
        records = list.sorted { ($0.isValid ? 0 : 1) < ($1.isValid ? 0 : 1) }
        selectedIds.formIntersection(Set(records.map { $0.id }))
    }

    private func toggleSelect(_ id: Int64) {
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
    }

    private func exitSelection() {
        selectionMode = false
        selectedIds.removeAll()
    }

    private func applyBatchValid(_ valid: Bool) async {
        if selectedIds.isEmpty {
            toast = "请先选择记录"
            return
        }
        try? await store.setExamRecordsValid(Array(selectedIds), valid)
        toast = valid ? "已将 \(selectedIds.count) 场记录设为有效" : "已将 \(selectedIds.count) 场记录设为无效"
        exitSelection()
        await load()
    }
}

// MARK: - 已清空的历史：软删除记录，可查看/恢复

struct ClearedExamHistoryView: View {
    @State private var records: [ExamRecord] = []
    @State private var pendingRestore: ExamRecord?
    @State private var showDetail = false
    @State private var detailRecord: ExamRecord?
    @State private var toast: String?

    private let store = QuestionStore.shared

    var body: some View {
        Group {
            if records.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "trash")
                        .font(.system(size: 44))
                        .foregroundColor(.secondary)
                    Text("暂无已清空的考试记录").foregroundColor(.secondary)
                }
            } else {
                List {
                    ForEach(records) { r in
                        rowButton(r)
                    }
                }
            }
        }
        .navigationTitle("已清空的历史")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: Binding(
            get: { showDetail && detailRecord != nil },
            set: { if !$0 { showDetail = false; detailRecord = nil } }
        )) {
            if let r = detailRecord {
                ExamHistoryDetailView(recordId: r.id, recordTitle: r.title)
            }
        }
        .confirmationDialog(
            pendingRestore?.title ?? "",
            isPresented: Binding(get: { pendingRestore != nil },
                                 set: { if !$0 { pendingRestore = nil } }),
            titleVisibility: .visible
        ) {
            Button("恢复") {
                if let r = pendingRestore {
                    Task {
                        try? await store.restoreExamRecord(r.id)
                        pendingRestore = nil
                        toast = "已恢复到考试管理"
                        await load()
                    }
                }
            }
            Button("取消", role: .cancel) { pendingRestore = nil }
        } message: {
            Text("恢复后该记录将重新显示在考试管理中")
        }
        .toast($toast)
        .task { await load() }
    }

    private func rowButton(_ r: ExamRecord) -> some View {
        let rate = r.totalCount > 0 ? Double(r.correctCount) * 100.0 / Double(r.totalCount) : 0.0
        return Button {
            detailRecord = r
            showDetail = true
        } label: {
            VStack(alignment: .leading, spacing: 5) {
                Text(r.title).font(.subheadline).bold().foregroundColor(.primary)
                Text(Fmt.dateTimeText(r.createdAt))
                    .font(.caption2).foregroundColor(.secondary)
                Text("得分 \(Fmt.rateText(r.correctCount, r.totalCount))")
                    .font(.caption).bold()
                    .foregroundColor(Theme.scoreColor(rate))
            }
            .contentShape(Rectangle())
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button { pendingRestore = r } label: { Label("恢复", systemImage: "arrow.uturn.left") }
                .tint(Theme.ok)
        }
    }

    private func load() async {
        records = await store.getClearedExamRecords()
    }
}

// MARK: - 考试历史详情：试卷快照逐题回顾

struct ExamHistoryDetailView: View {
    let recordId: Int64
    let recordTitle: String

    @State private var record: ExamRecord?
    @State private var answers: [ExamAnswer] = []
    @State private var loaded = false
    @State private var toast: String?

    private let store = QuestionStore.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let r = record {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(r.title).font(.headline)
                        Text(Fmt.dateTimeText(r.createdAt))
                            .font(.caption).foregroundColor(.secondary)
                        let rate = r.totalCount > 0 ? Double(r.correctCount) * 100.0 / Double(r.totalCount) : 0.0
                        Text("得分 \(Fmt.rateText(r.correctCount, r.totalCount))")
                            .font(.subheadline).bold()
                            .foregroundColor(Theme.scoreColor(rate))
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.card)
                    .cornerRadius(12)

                    ForEach(answers.indices, id: \.self) { i in
                        answerCard(answers[i])
                    }
                } else if loaded {
                    Text("该考试记录已被删除")
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 60)
                }
            }
            .padding(14)
        }
        .background(Theme.background)
        .navigationTitle(recordTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toast($toast)
        .task {
            record = await store.getExamRecordById(recordId)
            answers = await store.getExamAnswers(recordId)
            loaded = true
            if record == nil {
                toast = "该考试记录已被删除"
            }
        }
    }

    private func answerCard(_ ans: ExamAnswer) -> some View {
        let snapshot = Question(bankId: 0, type: ans.type, question: ans.question,
                                options: ans.options, answer: ans.answer, analysis: ans.analysis)
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(Theme.typeName(ans.type))
                    .font(.caption).bold()
                    .foregroundColor(Theme.primary)
                Text(ans.isCorrect ? "  ✓ 正确" : "  ✗ 错误")
                    .font(.caption).bold()
                    .foregroundColor(ans.isCorrect ? Theme.ok : Theme.danger)
                Spacer()
            }
            Text("\(ans.orderIndex + 1). \(ans.question)")
                .font(.subheadline).bold()
                .fixedSize(horizontal: false, vertical: true)

            if ans.type == "essay" {
                labeledText("你的解答：", ans.userAnswer, ans.isCorrect ? Theme.ok : Theme.danger)
                labeledText("参考答案：", ans.answer, Theme.ok)
            } else {
                let options = JsonImporter.parseOptions(ans.options)
                let correctOpts = Set(JsonImporter.getCorrectOptions(snapshot))
                let userOpts: Set<String> = ans.userAnswer == "未作答"
                    ? [] : Set(ans.userAnswer.components(separatedBy: "；"))
                VStack(spacing: 4) {
                    ForEach(options, id: \.self) { opt in
                        Text(opt)
                            .font(.caption)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 10).padding(.vertical, 8)
                            .background(optionBg(opt, correct: correctOpts, user: userOpts))
                            .cornerRadius(4)
                    }
                }
                labeledText("你的作答：", ans.userAnswer, ans.isCorrect ? Theme.ok : Theme.danger)
                labeledText("正确答案：", JsonImporter.getAnswerDisplay(snapshot), Theme.ok)
            }
            if let analysis = ans.analysis, !analysis.isEmpty {
                labeledText("解析：", analysis, .secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card)
        .cornerRadius(12)
    }

    private func optionBg(_ opt: String, correct: Set<String>, user: Set<String>) -> Color {
        if correct.contains(opt) { return Theme.correctBg }
        if user.contains(opt) { return Theme.wrongBg }
        return .clear
    }

    private func labeledText(_ prefix: String, _ content: String, _ color: Color) -> some View {
        Text(prefix + content)
            .font(.caption)
            .foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
    }
}
