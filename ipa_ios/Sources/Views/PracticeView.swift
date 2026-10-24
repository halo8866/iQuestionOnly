import SwiftUI

// MARK: - 练习/考试入口参数

struct ExamConfig: Hashable {
    var subjectId: Int64
    var subjectName: String
    var chapterIds: [Int64]?
    var single: Int
    var multiple: Int
    var judge: Int
    var essay: Int
    var durationMinutes: Int
}

enum PracticeSource: Hashable {
    case chapter(id: Int64, name: String, random: Bool)
    case exam(ExamConfig)
    case wrong(subjectId: Int64, title: String, questionIds: [Int64])
    case resumeExam(subjectId: Int64, subjectName: String, durationMinutes: Int)
}

// MARK: - 练习/考试逻辑（Android PracticeActivity 的移植）

@MainActor
final class PracticeVM: ObservableObject {
    let source: PracticeSource

    @Published var questions: [Question] = []
    @Published var currentIndex = 0
    @Published var userSelections: [Int: [String]] = [:]
    @Published var resultMap: [Int: Bool] = [:]
    @Published var correctCount = 0
    @Published var examSubmitted = false
    @Published var finished = false
    @Published var remainingSeconds = 0
    @Published var timerRunning = false
    @Published var timerPaused = false
    @Published var toast: String?
    @Published var showEndExamConfirm = false
    @Published var showHandInConfirm = false
    @Published var showTimeUp = false

    private let store = QuestionStore.shared
    private var examRecordSaved = false
    private var tickTask: Task<Void, Never>?
    private var timeUpTask: Task<Void, Never>?
    private let title: String

    init(source: PracticeSource) {
        self.source = source
        switch source {
        case .chapter(_, let name, _): title = name
        case .exam(let c): title = c.subjectName
        case .wrong(_, let t, _): title = t
        case .resumeExam(_, let n, _): title = n
        }
    }

    var displayTitle: String { isExam ? "\(title)考试" : title }

    var isExam: Bool {
        if case .chapter = source { return false }
        return true
    }

    var examDurationMinutes: Int {
        switch source {
        case .exam(let c): return c.durationMinutes
        case .resumeExam(_, _, let m): return m
        default: return 0
        }
    }

    var timerLabel: String {
        let m = remainingSeconds / 60
        let s = remainingSeconds % 60
        var text = String(format: "%02d:%02d", m, s)
        if timerPaused { text += " 已暂停" }
        return text
    }

    var currentQuestion: Question? {
        questions.indices.contains(currentIndex) ? questions[currentIndex] : nil
    }

    var currentSelection: [String] { userSelections[currentIndex] ?? [] }

    /// 当前题的作答区是否锁定
    var currentLocked: Bool {
        if isExam { return examSubmitted }
        if resultMap[currentIndex] != nil { return true }
        guard let q = currentQuestion else { return false }
        return q.type == "essay" && !currentSelection.isEmpty
    }

    /// 练习模式是否显示提交按钮（多选/解答需要手动提交）
    var showSubmitButton: Bool {
        !isExam && !currentLocked
            && (currentQuestion?.type == "multiple" || currentQuestion?.type == "essay")
    }

    // ---- 加载 ----

    func load() async {
        var raw: [Question] = []
        var draft: ExamDraft? = nil
        switch source {
        case .chapter(let id, _, _):
            raw = await store.getQuestionsByChapter(id)
        case .wrong(let subjectId, _, let ids):
            let items = await store.getWrongQuestionsBySubject(subjectId)
            raw = items
                .filter { $0.isValid && (ids.isEmpty || ids.contains($0.id)) }
                .map {
                    Question(id: $0.id, bankId: $0.bankId, chapterId: $0.chapterId,
                             type: $0.type, question: $0.question, options: $0.options,
                             answer: $0.answer, analysis: $0.analysis, isValid: $0.isValid)
                }
        case .exam(let cfg):
            let all: [Question]
            if let cids = cfg.chapterIds, !cids.isEmpty {
                all = await store.getValidQuestionsByChapters(cids)
            } else {
                all = await store.getValidQuestionsBySubject(cfg.subjectId)
            }
            let counts: [String: Int] = ["single": cfg.single, "multiple": cfg.multiple,
                                         "judge": cfg.judge, "essay": cfg.essay]
            let grouped = Dictionary(grouping: all, by: { $0.type })
            for t in ["single", "multiple", "judge", "essay"] {
                raw.append(contentsOf: (grouped[t] ?? []).shuffled().prefix(counts[t] ?? 0))
            }
        case .resumeExam:
            draft = ExamDraftStore.load()
            ExamDraftStore.clear()
        }
        if let draft {
            var loaded = await store.getQuestionsByIds(draft.questionIds)
            let order = Dictionary(draft.questionIds.enumerated().map { ($1, $0) },
                                   uniquingKeysWith: { first, _ in first })
            loaded.sort { (order[$0.id] ?? Int.max) < (order[$1.id] ?? Int.max) }
            raw = loaded
        }
        questions = arrange(raw)
        if let draft {
            currentIndex = min(draft.currentIndex, max(questions.count - 1, 0))
            userSelections = draft.userSelections
            if draft.remainingMillis > 0 {
                remainingSeconds = Int(draft.remainingMillis / 1000)
            }
        }
        if isExam && examDurationMinutes > 0 {
            if remainingSeconds <= 0 { remainingSeconds = examDurationMinutes * 60 }
            startTimer()
        }
    }

    /// 按模式排列题目：顺序保持 / 乱序打乱 / 考试按题型大类排序（类内随机）
    private func arrange(_ list: [Question]) -> [Question] {
        if case .chapter(_, _, let random) = source, random { return list.shuffled() }
        if isExam {
            let grouped = Dictionary(grouping: list, by: { $0.type })
            var result: [Question] = []
            for t in ["single", "multiple", "judge", "essay"] {
                result.append(contentsOf: (grouped[t] ?? []).shuffled())
            }
            let known = Set(["single", "multiple", "judge", "essay"])
            result.append(contentsOf: list.filter { !known.contains($0.type) }.shuffled())
            return result
        }
        return list
    }

    // ---- 倒计时 ----

    private func startTimer() {
        timerRunning = true
        timerPaused = false
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let s = self else { return }
                s.tick()
            }
        }
    }

    private func stopTimer() {
        timerRunning = false
        tickTask?.cancel()
        tickTask = nil
    }

    private func tick() {
        guard timerRunning, !timerPaused, isExam, !examSubmitted, !finished else { return }
        if remainingSeconds > 0 { remainingSeconds -= 1 }
        if remainingSeconds == 0 { onTimeUp() }
    }

    func togglePause() {
        timerPaused.toggle()
    }

    private func onTimeUp() {
        guard !examSubmitted else { return }
        stopTimer()
        showTimeUp = true
        timeUpTask?.cancel()
        timeUpTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let s = self, !Task.isCancelled else { return }
            await s.handIn()
        }
    }

    func confirmTimeUpHandIn() {
        timeUpTask?.cancel()
        handIn()
    }

    // ---- 作答 ----

    func setSelection(_ opts: [String]) {
        userSelections[currentIndex] = opts
    }

    /// 单选/判断点选选项
    func pickSingle(_ opt: String) {
        guard !currentLocked else { return }
        guard let q = currentQuestion else { return }
        setSelection([opt])
        if q.type == "single" || q.type == "judge" {
            if isExam {
                // 考试模式：保存作答并自动跳下一题（最后一题除外）
                if currentIndex < questions.count - 1 { currentIndex += 1 }
            } else {
                submitAnswer()
            }
        }
    }

    func toggleMultiple(_ opt: String) {
        guard !currentLocked else { return }
        var sel = currentSelection
        if let idx = sel.firstIndex(of: opt) {
            sel.remove(at: idx)
        } else {
            sel.append(opt)
        }
        setSelection(sel)
    }

    /// 解答题文本输入
    func setEssayText(_ text: String) {
        setSelection(text.isEmpty ? [] : [text])
    }

    /// 练习模式单题提交
    func submitAnswer() {
        if isExam { return }
        guard resultMap[currentIndex] == nil else { return }
        guard let q = currentQuestion else { return }
        let sel = currentSelection
        if sel.isEmpty {
            toast = "请先作答"
            return
        }
        userSelections[currentIndex] = sel
        let id = q.id
        Task { try? await store.markQuestionDone(id) }
        if q.type == "essay" {
            // 解答题：等待自评
        } else {
            let ok = JsonImporter.isAnswerCorrect(userAnswer: sel, question: q)
            resultMap[currentIndex] = ok
            if ok { correctCount += 1 }
            syncWrongRecord(q, sel, ok)
        }
    }

    /// 解答题自评
    func selfRate(_ ok: Bool) {
        guard resultMap[currentIndex] == nil else { return }
        guard let q = currentQuestion else { return }
        resultMap[currentIndex] = ok
        if ok { correctCount += 1 }
        syncWrongRecord(q, userSelections[currentIndex] ?? [], ok)
    }

    /// 判错 → 记入/刷新错题本；判对 → 消除错题
    private func syncWrongRecord(_ q: Question, _ sel: [String], _ ok: Bool) {
        let id = q.id
        let joined = sel.joined(separator: "；")
        let wrongAnswer = joined.isEmpty ? "未作答" : joined
        if ok {
            Task { try? await store.deleteWrongByQuestion(id) }
        } else {
            Task { try? await store.upsertWrongRecord(questionId: id, wrongAnswer: wrongAnswer) }
        }
    }

    // ---- 翻页 / 交卷 ----

    func goPrev() {
        if currentIndex > 0 { currentIndex -= 1 }
    }

    func goNext() {
        let isLast = currentIndex >= questions.count - 1
        if isExam && !examSubmitted && isLast {
            showHandInConfirm = true
            return
        }
        if isExam && examSubmitted && isLast {
            let unrated = questions.count - resultMap.count
            if unrated > 0 { toast = "还有 \(unrated) 道解答题未自评，未自评将记为错误" }
            finished = true
            return
        }
        if !isExam && isLast {
            let unanswered = questions.count - resultMap.count
            if unanswered > 0 { toast = "还有 \(unanswered) 题未作答/未自评，将记为错误" }
            finished = true
            return
        }
        currentIndex += 1
    }

    func handIn() {
        guard !examSubmitted else { return }
        stopTimer()
        showTimeUp = false
        showHandInConfirm = false
        ExamDraftStore.clear()
        // 已作答的题目计入章节进度
        let answeredIds = questions.enumerated()
            .filter { !(userSelections[$0.offset] ?? []).isEmpty }
            .map { $0.element.id }
        Task { for id in answeredIds { try? await store.markQuestionDone(id) } }
        // 客观题自动判分；解答题在查看模式中自评
        for (i, q) in questions.enumerated() {
            if q.type != "essay" {
                let sel = userSelections[i] ?? []
                let ok = !sel.isEmpty && JsonImporter.isAnswerCorrect(userAnswer: sel, question: q)
                resultMap[i] = ok
                if ok { correctCount += 1 }
                syncWrongRecord(q, sel, ok)
            }
        }
        examSubmitted = true
        currentIndex = 0
        finished = true
    }

    // ---- 考试历史快照 ----

    func saveExamRecordIfNeeded() async {
        guard isExam, examSubmitted, !examRecordSaved else { return }
        examRecordSaved = true
        let answers = questions.enumerated().map { (i, q) -> ExamAnswer in
            let sel = userSelections[i] ?? []
            let ua: String
            if q.type == "essay" {
                let t = (sel.first ?? "").trimmingCharacters(in: .whitespaces)
                ua = t.isEmpty ? "未作答" : t
            } else {
                ua = sel.isEmpty ? "未作答" : sel.joined(separator: "；")
            }
            return ExamAnswer(recordId: 0, orderIndex: i, type: q.type,
                              question: q.question, options: q.options, answer: q.answer,
                              analysis: q.analysis, userAnswer: ua,
                              isCorrect: resultMap[i] == true)
        }
        let record = ExamRecord(title: title, totalCount: questions.count, correctCount: correctCount)
        try? await store.insertExamWithAnswers(record, answers)
    }

    // ---- 退出 / 暂存 / 重开 ----

    /// 返回键处理；返回 true 表示可以直接退出
    func requestExit() async -> Bool {
        if isExam && !examSubmitted {
            showEndExamConfirm = true
            return false
        }
        if isExam && examSubmitted && !finished {
            finished = true
            return false
        }
        await saveExamRecordIfNeeded()
        return true
    }

    /// 暂存考试并退出；返回 true 表示保存成功
    func saveDraftAndExit() -> Bool {
        guard isExam, !examSubmitted, !questions.isEmpty else { return false }
        var subjectId: Int64 = 0
        var subjectName = ""
        var chapterIds: [Int64] = []
        var cSingle = 0, cMultiple = 0, cJudge = 0, cEssay = 0
        switch source {
        case .exam(let c):
            subjectId = c.subjectId
            subjectName = c.subjectName
            chapterIds = c.chapterIds ?? []
            cSingle = c.single; cMultiple = c.multiple; cJudge = c.judge; cEssay = c.essay
        case .resumeExam(let sid, let sname, _):
            subjectId = sid
            subjectName = sname
        default: return false
        }
        let draft = ExamDraft(
            subjectId: subjectId, subjectName: subjectName, chapterIds: chapterIds,
            countSingle: cSingle, countMultiple: cMultiple, countJudge: cJudge, countEssay: cEssay,
            durationMinutes: examDurationMinutes,
            remainingMillis: Int64(remainingSeconds) * 1000,
            questionIds: questions.map { $0.id },
            currentIndex: currentIndex,
            userSelections: userSelections,
            savedAt: Date.nowMillis)
        ExamDraftStore.save(draft)
        stopTimer()
        return true
    }

    /// 成绩页 → 再来一次
    func restart() async {
        await saveExamRecordIfNeeded()
        currentIndex = 0
        correctCount = 0
        examSubmitted = false
        examRecordSaved = false
        userSelections = [:]
        resultMap = [:]
        finished = false
        remainingSeconds = examDurationMinutes > 0 ? examDurationMinutes * 60 : 0
        if case .exam = source {
            questions = []
            await load()
        } else {
            questions = arrange(questions)
        }
        if isExam && examDurationMinutes > 0 { startTimer() }
    }

    // ---- 当前题的展示状态 ----

    var showResultPanel: Bool {
        guard currentQuestion != nil else { return false }
        if isExam { return examSubmitted }
        return resultMap[currentIndex] != nil || awaitingSelfRate
    }

    var awaitingSelfRate: Bool {
        guard let q = currentQuestion, q.type == "essay" else { return false }
        if resultMap[currentIndex] != nil { return false }
        if isExam { return examSubmitted }
        return !currentSelection.isEmpty
    }

    /// 选项底色：正确绿底、错选红底
    func optionBackground(_ opt: String) -> Color? {
        guard showResultPanel, let q = currentQuestion, q.type != "essay" else { return nil }
        if JsonImporter.getCorrectOptions(q).contains(opt) { return Theme.correctBg }
        if currentSelection.contains(opt) { return Theme.wrongBg }
        return nil
    }
}

// MARK: - 练习/考试视图

struct PracticeView: View {
    @StateObject private var vm: PracticeVM
    @Environment(\.dismiss) private var dismiss

    init(source: PracticeSource) {
        _vm = StateObject(wrappedValue: PracticeVM(source: source))
    }

    var body: some View {
        VStack(spacing: 0) {
            if vm.questions.isEmpty {
                ProgressView("正在加载题目…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.finished {
                finishView
            } else {
                questionView
            }
        }
        .background(Theme.background)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    Task { if await vm.requestExit() { dismiss() } }
                } label: {
                    Image(systemName: "chevron.left")
                }
            }
            ToolbarItem(placement: .principal) {
                Text(vm.displayTitle).font(.headline).lineLimit(1)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 8) {
                    if vm.timerRunning {
                        Button(action: { vm.togglePause() }) {
                            Text(vm.timerLabel)
                                .font(.subheadline).bold()
                                .foregroundColor(vm.remainingSeconds <= 60 ? .red : Theme.primary)
                        }
                    }
                    if vm.isExam && !vm.examSubmitted && !vm.finished {
                        Menu {
                            Button("暂存考试") {
                                if vm.saveDraftAndExit() {
                                    dismiss()
                                }
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
            }
        }
        .confirmationDialog("结束考试", isPresented: $vm.showEndExamConfirm, titleVisibility: .visible) {
            Button("结束并交卷", role: .destructive) { vm.handIn() }
            Button("继续作答", role: .cancel) { }
        } message: {
            Text(endExamMessage)
        }
        .confirmationDialog("交卷", isPresented: $vm.showHandInConfirm, titleVisibility: .visible) {
            Button("确认交卷", role: .destructive) { vm.handIn() }
            Button("继续作答", role: .cancel) { }
        } message: {
            Text(handInMessage)
        }
        .alert("考试时间到", isPresented: $vm.showTimeUp) {
            Button("立即交卷") { vm.confirmTimeUpHandIn() }
        } message: {
            Text("时间已到，将自动交卷判分")
        }
        .toast($vm.toast)
        .task {
            await vm.load()
            if vm.questions.isEmpty { dismiss() }
        }
    }

    private var answeredCount: Int {
        vm.userSelections.values.filter { !$0.isEmpty }.count
    }

    private var unansweredCount: Int { vm.questions.count - answeredCount }

    private var handInMessage: String {
        var msg = "交卷后将自动判分客观题"
        if unansweredCount > 0 {
            msg += "\n\n你还有 \(unansweredCount) 道题未作答（已答 \(answeredCount) 道），未作答将记为错误。"
        }
        return msg
    }

    private var endExamMessage: String {
        var msg = "结束考试将立即交卷判分"
        if unansweredCount > 0 {
            msg += "\n\n你还有 \(unansweredCount) 道题未作答（已答 \(answeredCount) 道），未作答将记为错误。"
        }
        return msg
    }

    // MARK: 答题区

    private var questionView: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let q = vm.currentQuestion {
                        progressHeader(q)
                        optionsArea(q)
                        if vm.showResultPanel { resultPanel(q) }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            navBar
        }
    }

    private func progressHeader(_ q: Question) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(examProgressText)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Spacer()
                Text(Theme.typeName(q.type))
                    .font(.caption).bold()
                    .foregroundColor(.white)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Theme.primary))
            }
            Text(q.question)
                .font(.body).bold()
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var examProgressText: String {
        if vm.isExam {
            return "第 \(vm.currentIndex + 1) / \(vm.questions.count) 题 · 已答 \(answeredCount)"
        }
        return "第 \(vm.currentIndex + 1) / \(vm.questions.count) 题"
    }

    @ViewBuilder
    private func optionsArea(_ q: Question) -> some View {
        let options = JsonImporter.parseOptions(q.options)
        let locked = vm.currentLocked
        if q.type == "essay" {
            let text = Binding(
                get: { vm.currentSelection.first ?? "" },
                set: { vm.setEssayText($0) })
            TextEditor(text: text)
                .frame(minHeight: 120)
                .disabled(locked)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color.secondary.opacity(0.3))
                )
                .overlay(alignment: .topLeading) {
                    if vm.currentSelection.isEmpty && !locked {
                        Text("在此输入你的解答…")
                            .foregroundColor(.secondary)
                            .padding(12)
                            .allowsHitTesting(false)
                    }
                }
        } else {
            VStack(spacing: 0) {
                ForEach(options, id: \.self) { opt in
                    optionRow(opt, locked: locked, showResult: vm.showResultPanel)
                    Divider()
                }
            }
            .background(Theme.card)
            .cornerRadius(10)
        }
    }

    private func optionRow(_ opt: String, locked: Bool, showResult: Bool) -> some View {
        let isSelected = vm.currentSelection.contains(opt)
        let bg = vm.optionBackground(opt)
        return Button {
            if let q = vm.currentQuestion {
                if q.type == "multiple" { vm.toggleMultiple(opt) } else { vm.pickSingle(opt) }
            }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                if vm.currentQuestion?.type == "multiple" {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isSelected ? Theme.primary : .secondary)
                } else {
                    Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                        .foregroundColor(isSelected ? Theme.primary : .secondary)
                }
                Text(opt)
                    .font(.body)
                    .foregroundColor(.primary)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 13)
            .background(bg ?? Color.clear)
            .contentShape(Rectangle())
        }
        .disabled(locked)
    }

    @ViewBuilder
    private func resultPanel(_ q: Question) -> some View {
        let ok = vm.resultMap[vm.currentIndex] ?? false
        let isEssay = q.type == "essay"
        VStack(alignment: .leading, spacing: 8) {
            if vm.awaitingSelfRate {
                Text("请对照参考答案自评")
                    .font(.subheadline).bold()
                    .foregroundColor(Theme.primary)
            } else {
                Text(ok ? "回答正确" : "回答错误")
                    .font(.subheadline).bold()
                    .foregroundColor(ok ? Theme.ok : Theme.danger)
            }
            if isEssay {
                labeledText("你的解答：", vm.currentSelection.first ?? "未作答",
                            vm.resultMap[vm.currentIndex] == true ? Theme.ok : Theme.danger)
                labeledText("参考答案：", q.answer, Theme.ok)
            } else {
                labeledText("你的作答：", vm.currentSelection.joined(separator: "；").isEmpty ? "未作答" : vm.currentSelection.joined(separator: "；"),
                            ok ? Theme.ok : Theme.danger)
                labeledText("正确答案：", JsonImporter.getAnswerDisplay(q), Theme.ok)
            }
            if let analysis = q.analysis, !analysis.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                labeledText("解析：", analysis, .secondary)
            }
            if vm.awaitingSelfRate {
                HStack(spacing: 12) {
                    Button { vm.selfRate(true) } label: {
                        Text("我做对了").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.ok)
                    Button { vm.selfRate(false) } label: {
                        Text("我做错了").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.danger)
                }
                .padding(.top, 4)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card)
        .cornerRadius(12)
    }

    private func labeledText(_ prefix: String, _ content: String, _ color: Color) -> some View {
        Text(prefix + content)
            .font(.subheadline)
            .foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var navBar: some View {
        HStack(spacing: 12) {
            Button { vm.goPrev() } label: { Text("上一题").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
                .disabled(vm.currentIndex == 0)
            if vm.showSubmitButton {
                Button { vm.submitAnswer() } label: { Text("提交").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            }
            Button { vm.goNext() } label: { Text(nextButtonTitle).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .tint(nextButtonTint)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Theme.card)
    }

    private var nextButtonTitle: String {
        let isLast = vm.currentIndex >= vm.questions.count - 1
        if !isLast { return "下一题" }
        if vm.isExam && !vm.examSubmitted { return "交卷" }
        if vm.isExam && vm.examSubmitted { return "查看成绩" }
        return "完成"
    }

    private var nextButtonTint: Color {
        let isLast = vm.currentIndex >= vm.questions.count - 1
        return (isLast && vm.isExam && !vm.examSubmitted) ? Theme.danger : Theme.primary
    }

    // MARK: 成绩页

    private var finishView: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "checkmark.seal")
                .font(.system(size: 56))
                .foregroundColor(Theme.primary)
            let rate = vm.questions.count > 0
                ? Double(vm.correctCount) * 100.0 / Double(vm.questions.count) : 0.0
            Text(vm.isExam ? "考试成绩" : "练习完成")
                .font(.title3).bold()
            Text(Fmt.rateText(vm.correctCount, vm.questions.count))
                .font(.system(size: 34, weight: .bold))
                .foregroundColor(Theme.scoreColor(rate))
            let unrated = vm.questions.count - vm.resultMap.count
            if vm.isExam && unrated > 0 {
                Text("还有 \(unrated) 道解答题未自评，未自评将记为错误")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            Spacer()
            VStack(spacing: 12) {
                if vm.isExam {
                    Button { vm.finished = false; vm.currentIndex = 0 } label: {
                        Text("查看题目解析").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                Button { Task { await vm.restart() } } label: {
                    Text("再来一次").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button {
                    Task { if await vm.requestExit() { dismiss() } }
                } label: {
                    Text("退出").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.secondary)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
    }
}
