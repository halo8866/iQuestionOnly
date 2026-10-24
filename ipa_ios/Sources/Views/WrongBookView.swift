import SwiftUI

// MARK: - 科目错题本（Android WrongBookActivity 的移植）
// 展示该科目所有错题（含上次错误作答），支持按章节筛选、按关键词搜索（题目/答案/解析），
// 支持一键错题练习；练习中答对会自动从错题本消除

struct WrongBookView: View {
    let subjectId: Int64
    let subjectName: String

    @State private var allWrong: [WrongQuestionItem] = []
    @State private var chapters: [Chapter] = []
    @State private var filterChapterId: Int64?
    @State private var searchKeyword = ""
    @State private var practiceRoute: PracticeSource?

    var body: some View {
        List {
            Section {
                TextField("搜索题目 / 答案 / 解析", text: $searchKeyword)
                    .textFieldStyle(.roundedBorder)
                Picker("章节", selection: $filterChapterId) {
                    Text("全部章节").tag(Int64?.none)
                    ForEach(chapters) { c in
                        Text(c.name).tag(Int64?.some(c.id))
                    }
                }
            }

            if displayList.isEmpty {
                Section {
                    Text(emptyHint)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 32)
                }
            } else {
                ForEach(displayList) { item in
                    wrongRow(item)
                }
            }
        }
        .navigationTitle("错题本")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Button {
                startWrongPractice()
            } label: {
                Text("开始错题练习（\(practiceCount) 题）")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(practiceCount == 0)
            .padding(.horizontal)
            .padding(.bottom, 8)
            .background(.ultraThinMaterial)
        }
        .navigationDestination(isPresented: Binding(
            get: { practiceRoute != nil },
            set: { if !$0 { practiceRoute = nil } }
        )) {
            if let r = practiceRoute { PracticeView(source: r) }
        }
        .task { await load() }
    }

    /// 按章节 + 关键词筛选后的展示列表
    private var displayList: [WrongQuestionItem] {
        let kw = searchKeyword.lowercased()
        return allWrong.filter { item in
            let chapterOk = filterChapterId == nil || item.chapterId == filterChapterId
            let keywordOk = kw.isEmpty ||
                item.question.lowercased().contains(kw) ||
                item.answer.lowercased().contains(kw) ||
                (item.analysis?.lowercased().contains(kw) == true)
            return chapterOk && keywordOk
        }
    }

    /// 练习数 = 当前筛选结果中的有效错题
    private var practiceCount: Int {
        displayList.filter { $0.isValid }.count
    }

    private var emptyHint: String {
        allWrong.isEmpty ? "暂无错题，去练习里挑战一下吧" : "没有符合筛选条件的错题"
    }

    private func load() async {
        chapters = await QuestionStore.shared.getChaptersBySubject(subjectId)
        allWrong = await QuestionStore.shared.getWrongQuestionsBySubject(subjectId)
    }

    /// 错题练习：复用练习页，题目来源为当前筛选结果中的有效错题；答对自动消除
    private func startWrongPractice() {
        let validIds = displayList.filter { $0.isValid }.map { $0.id }
        guard !validIds.isEmpty else { return }
        practiceRoute = .wrong(subjectId: subjectId,
                               title: "\(subjectName) · 错题练习",
                               questionIds: validIds)
    }

    private func wrongRow(_ item: WrongQuestionItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(Theme.typeName(item.type))
                    .font(.caption2).bold()
                    .foregroundColor(Theme.primary)
                Text(item.chapterName)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Text(Fmt.dateTimeText(item.wrongAt))
                    .font(.caption2)
                    .foregroundColor(.secondary)
                if !item.isValid {
                    Text("无效")
                        .font(.caption2).bold()
                        .foregroundColor(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Capsule().fill(Color.gray))
                }
            }
            Text(item.question)
                .font(.subheadline)
                .foregroundColor(.primary)
            Text("上次作答：\(item.wrongAnswer)")
                .font(.caption)
                .foregroundColor(.red)
        }
        .opacity(item.isValid ? 1.0 : 0.55)
        .contentShape(Rectangle())
    }
}
