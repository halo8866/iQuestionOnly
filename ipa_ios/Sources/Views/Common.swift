import SwiftUI
import UniformTypeIdentifiers

// MARK: - 公共组件

extension Theme {
    /// 结果高亮底色（与 Android #C8E6C9 / #FFCDD2 对齐）
    static let correctBg = Color(red: 0.784, green: 0.902, blue: 0.788)
    static let wrongBg = Color(red: 1.0, green: 0.804, blue: 0.824)

    /// 成绩颜色：60 分及以上为绿
    static func scoreColor(_ rate: Double) -> Color { rate >= 60 ? .green : .red }
}

// MARK: 日期格式化

enum Fmt {
    static let dateTime: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    static func dateTimeText(_ millis: Int64) -> String {
        dateTime.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    static func rateText(_ correct: Int, _ total: Int) -> String {
        let rate = total > 0 ? Double(correct) * 100.0 / Double(total) : 0.0
        return String(format: "%d/%d（%.2f%%）", correct, total, rate)
    }
}

// MARK: 轻量 Toast（模拟 Android Toast）

struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let m = message {
                Text(m)
                    .font(.footnote)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.75)))
                    .padding(.bottom, 48)
                    .transition(.opacity)
                    .task {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        withAnimation { if message == m { message = nil } }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}

// MARK: 文件导出用文本文档（JSON 备份/模板）

struct TextFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    static var writableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

// MARK: JSON 响应中的数字解析工具

enum JsonNum {
    static func int(_ any: Any?) -> Int {
        if let i = any as? Int { return i }
        if let d = any as? Double { return Int(d) }
        if let s = any as? String { return Int(s) ?? 0 }
        return 0
    }

    static func int64(_ any: Any?) -> Int64 { Int64(int(any)) }
}
