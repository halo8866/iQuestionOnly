# 题梦 iQuestion Only

题梦是一款**纯本地**的题库练习与模拟考试应用，提供 Android 与 iOS 两个原生客户端。不需要注册账号，不连接任何服务器，题库、错题和考试记录全部保存在本机。

> 本仓库是纯本地开源版：已移除账号体系、云端上传/恢复、服务端与微信小程序端。完整版不再对外维护，本仓库仅保留 Android APK 与 iOS App。

## 功能特性

- **题库管理**：按「科目 → 章节 → 题目」组织，支持单选、多选、判断、解答四种题型
- **练习模式**：顺序练习、乱序练习；单选/判断题选中后自动提交并跳转
- **模拟考试**：自定义各题型数量与考试时长、倒计时、暂存/恢复考试、交卷与成绩解析
- **错题本**：答错自动收录，支持重做与筛选
- **考试历史**：留存历次试卷、作答与成绩，支持恢复和批量清理
- **导入导出**：通过 JSON 文件导入题库（自动创建科目/章节）、导出完整备份、下载模板
- **完全离线**：Android 端未声明 `INTERNET` 权限，数据仅保存在本机 SQLite / Room 数据库

## 目录结构

```
iQuestionOnly/
├── apk_android/       # Android 原生 App（Kotlin）
│   ├── src/main/java/com/iquestion/app/
│   │   ├── data/      # Room 数据库、实体、DAO
│   │   └── util/      # 导入导出、考试草稿
│   └── build.gradle
│
├── ipa_ios/           # iOS 原生 App（SwiftUI，XcodeGen 生成工程）
│   ├── project.yml    # XcodeGen 工程定义（bundle id: com.iquestion.app）
│   └── Sources/
│       ├── Data/      # SQLite（系统 libsqlite3）、模型、DAO
│       ├── Util/      # 导入导出、考试草稿
│       └── Views/     # 各页面 SwiftUI 视图
│
├── assets/            # 测试题库样例
├── .github/workflows/ # 双端打包发布流水线（手动触发）
├── LICENSE
└── README.md
```

## 技术栈

| 模块 | 技术 |
|---|---|
| apk_android | Kotlin 2.0、Android Gradle Plugin 8.11、minSdk 24 / targetSdk 34、Room、Coroutines、Gson |
| ipa_ios | SwiftUI、iOS 16+、系统 libsqlite3、XcodeGen |

## 下载与安装

- 前往本仓库 **Releases** 页面下载最新的 APK 或 IPA。
- Android：安装已签名 APK 即可使用。
- iOS：Release 中的 IPA 可能为未签名版本，需要自行使用 AltStore、TrollStore 或开发者证书签名后安装。

## 本地开发

### Android

用 Android Studio 打开 `apk_android/`，等待 Gradle 同步后运行；或使用命令行：

```bash
cd apk_android
./gradlew assembleDebug
```

### iOS

需要 macOS + Xcode。工程文件由 XcodeGen 生成，不提交到仓库：

```bash
cd ipa_ios
brew install xcodegen
xcodegen generate
open iQuestion.xcodeproj
```

## 构建 Release APK

在 `apk_android/keystore.properties` 中配置本地签名（该文件不入库）：

```properties
storeFile=你的.jks
storePassword=******
keyAlias=别名
keyPassword=******
```

```bash
cd apk_android
./gradlew assembleRelease
```

产物位于 `apk_android/build/outputs/apk/release/`。

**请勿把 keystore、签名密码或 `keystore.properties` 提交到 Git。**

## 自动发布（可选）

仓库内置 `.github/workflows/release.yml`，默认**仅手动触发**：在 GitHub 的 Actions 页面选择 `Release` → `Run workflow`，会构建 Android APK 与 iOS IPA 并创建版本化 Release。

如启用自动打包，需要在仓库 Secrets 中配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | Android 签名 keystore 的 Base64 内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 签名别名 |
| `KEY_PASSWORD` | 签名密码 |
| `IOS_P12_BASE64` | iOS 分发证书（可选） |
| `IOS_P12_PASSWORD` | 证书密码（可选） |
| `IOS_PROFILE_BASE64` | iOS Provisioning Profile（可选） |

未配置 iOS 签名相关 Secrets 时，流水线会产出未签名 IPA。

## 数据与隐私

- 所有题库、错题、考试记录仅保存在设备本地。
- 导入导出通过系统文件选择器完成，不经过任何服务器。
- 应用不包含账号、登录、云同步、埋点或广告 SDK。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

欢迎提交 Issue 与 Pull Request。
