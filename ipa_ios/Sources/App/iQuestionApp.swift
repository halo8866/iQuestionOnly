import SwiftUI

@main
struct iQuestionApp: App {
    var body: some Scene {
        WindowGroup {
            HomeView()
        }
    }
}

// MARK: - 主题（与 Android 端 Material 配色对齐）

enum Theme {
    static let primary = Color(red: 0.31, green: 0.27, blue: 0.90)   // Indigo 600
    static let background = Color(uiColor: .systemGroupedBackground)
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let danger = Color.red
    static let ok = Color.green

    /// 题型显示名
    static func typeName(_ type: String) -> String {
        switch type {
        case "single": return "单选"
        case "multiple": return "多选"
        case "judge": return "判断"
        case "essay": return "解答"
        default: return type
        }
    }
}
