import SwiftUI

// MARK: - 根视图：底部双 Tab（首页 / 我的），对应 Android 首页 + 我的

struct HomeView: View {
    var body: some View {
        TabView {
            HomeTab()
                .tabItem { Label("首页", systemImage: "house.fill") }
            MyTab()
                .tabItem { Label("我的", systemImage: "person.fill") }
        }
    }
}

// MARK: - 首页：科目列表

struct HomeTab: View {
    @State private var subjects: [SubjectWithCounts] = []
    @State private var showAdd = false
    @State private var newName = ""
    @State private var pendingDelete: SubjectWithCounts?
    @State private var toast: String?

    var body: some View {
        NavigationStack {
            Group {
                if subjects.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "tray")
                            .font(.system(size: 44))
                            .foregroundColor(.secondary)
                        Text("还没有科目\n点击右上角 + 添加")
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                } else {
                    List {
                        ForEach(subjects) { s in
                            NavigationLink(value: s) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(s.name).font(.headline)
                                    Text("\(s.chapterCount) 章节 · \(s.questionCount) 题 · 错题 \(s.wrongCount)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("题梦")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                }
            }
            .navigationDestination(for: SubjectWithCounts.self) { s in
                SubjectView(subjectId: s.id, subjectName: s.name)
            }
            .alert("添加科目", isPresented: $showAdd) {
                TextField("输入科目名称", text: $newName)
                Button("确定") { Task { await addSubject() } }
                Button("取消", role: .cancel) { }
            }
            .confirmationDialog(
                pendingDelete?.name ?? "",
                isPresented: Binding(get: { pendingDelete != nil },
                                     set: { if !$0 { pendingDelete = nil } }),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let s = pendingDelete {
                        Task {
                            try? await QuestionStore.shared.deleteSubject(s.id)
                            pendingDelete = nil
                            await load()
                        }
                    }
                }
                Button("取消", role: .cancel) { pendingDelete = nil }
            } message: {
                Text("删除科目将同时删除其下所有章节与题目，确定删除？")
            }
            .toast($toast)
            .task { await load() }
        }
    }

    private func load() async {
        subjects = await QuestionStore.shared.getAllSubjectsWithCounts()
    }

    private func addSubject() async {
        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            toast = "名称不能为空"
            return
        }
        if let _ = await QuestionStore.shared.findSubjectByName(name) {
            toast = "添加失败：科目已存在"
            return
        }
        do {
            _ = try await QuestionStore.shared.insertSubject(Subject(name: name))
            newName = ""
            await load()
        } catch {
            toast = "添加失败：\(error.localizedDescription)"
        }
    }
}

// MARK: - 我的：功能入口集中页

enum MyRoute: Hashable {
    case wrongBook(SubjectWithCounts)
    case manage(SubjectWithCounts)
    case wrongPicker
    case managePicker
    case examHistory
    case importExport
}

struct MyTab: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            List {
                entryRow(icon: "book.closed", title: "错题本", subtitle: "按科目回顾错题并针对性练习") {
                    path.append(MyRoute.wrongPicker)
                }
                entryRow(icon: "square.and.pencil", title: "题目管理", subtitle: "编辑、增删与批量管理题目") {
                    path.append(MyRoute.managePicker)
                }
                entryRow(icon: "doc.text", title: "考试管理", subtitle: "历次考试成绩与试卷回顾") {
                    path.append(MyRoute.examHistory)
                }
                entryRow(icon: "square.and.arrow.up.on.square", title: "导入导出", subtitle: "导入 JSON 题库 / 导出备份") {
                    path.append(MyRoute.importExport)
                }
            }
            .navigationTitle("我的")
            .navigationDestination(for: MyRoute.self) { route in
                switch route {
                case .wrongBook(let s): WrongBookView(subjectId: s.id, subjectName: s.name)
                case .manage(let s): QuestionManageView(subjectId: s.id, subjectName: s.name)
                case .wrongPicker: SubjectPickerPage(forWrong: true)
                case .managePicker: SubjectPickerPage(forWrong: false)
                case .examHistory: ExamHistoryView()
                case .importExport: ImportExportView()
                }
            }
        }
    }

    private func entryRow(icon: String, title: String, subtitle: String,
                          action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(Theme.primary)
                    .frame(width: 34)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline).foregroundColor(.primary)
                    Text(subtitle).font(.caption).foregroundColor(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}

// MARK: - 科目选择页（错题本/题目管理的入口，页面式推入而非弹窗）

struct SubjectPickerPage: View {
    let forWrong: Bool
    @State private var subjects: [SubjectWithCounts] = []

    var body: some View {
        List {
            let wrongOnly = subjects.filter { $0.wrongCount > 0 }
            let list = forWrong && !wrongOnly.isEmpty ? wrongOnly : subjects
            ForEach(list) { s in
                NavigationLink(value: forWrong ? MyRoute.wrongBook(s) : MyRoute.manage(s)) {
                    HStack {
                        Text(s.name)
                        Spacer()
                        Text(forWrong ? "错题 \(s.wrongCount)" : "\(s.questionCount) 题")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle(forWrong ? "错题本" : "题目管理")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if subjects.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "tray")
                        .font(.system(size: 44))
                        .foregroundColor(.secondary)
                    Text("还没有科目\n请先在「导入导出」中导入题库")
                        .multilineTextAlignment(.center)
                        .foregroundColor(.secondary)
                }
            }
        }
        .task { subjects = await QuestionStore.shared.getAllSubjectsWithCounts() }
    }
}
