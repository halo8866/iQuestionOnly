package com.iquestion.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.Chapter
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.data.Subject
import com.iquestion.app.databinding.ActivityImportExportBinding
import com.iquestion.app.databinding.DialogImportChoiceBinding
import com.iquestion.app.util.BackupExporter
import com.iquestion.app.util.JsonImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导入导出页：合并原「导入题库」与「导出全部题目」两个入口。
 * - 导入：选择 JSON 题库文件导入（科目/章节不存在自动创建），并可下载 JSON 模板
 * - 导出：把全部科目/章节/题目导出为可重新导入的备份 JSON
 */
class ImportExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportExportBinding
    private lateinit var dao: QuestionDao

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importFromUri(it) }
    }

    private val createTemplate = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { saveTemplate(it) }
    }

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { exportToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        dao = AppDatabase.getInstance(this).questionDao()

        binding.btnSelectFile.setOnClickListener {
            pickFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnDownloadTemplate.setOnClickListener {
            createTemplate.launch("题梦_题库模板.json")
        }
        binding.btnExport.setOnClickListener { startExport() }
    }

    // ---- 导出 ----

    private fun startExport() {
        lifecycleScope.launch {
            val subjects = withContext(Dispatchers.IO) { dao.getAllSubjectsWithCounts() }
            if (subjects.isEmpty() || subjects.all { it.questionCount == 0 }) {
                Toast.makeText(this@ImportExportActivity, R.string.msg_export_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            exportDocument.launch("题梦_备份_$stamp.json")
        }
    }

    private fun exportToUri(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val subjects = withContext(Dispatchers.IO) { dao.getAllSubjectsWithCounts() }
                val count = subjects.sumOf { it.questionCount }
                val json = withContext(Dispatchers.IO) { BackupExporter.buildJson(dao) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalArgumentException("无法创建文件")
                }
                showResult(true, getString(R.string.msg_export_success, count))
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.msg_export_success, count),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                showResult(false, getString(R.string.msg_export_failed, e.message))
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- 模板 ----

    private fun saveTemplate(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(buildTemplate().toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalArgumentException("无法创建文件")
                }
                showResult(true, getString(R.string.msg_template_saved))
                Toast.makeText(
                    this@ImportExportActivity,
                    R.string.msg_template_saved,
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                showResult(false, getString(R.string.msg_template_failed, e.message))
            }
        }
    }

    private fun buildTemplate(): String {
        return """{
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
      "analysis": "解析（可选）：2 和 4 是偶数；答案也可写成逗号分隔的字符串 \"A,C\""
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
      "answer": "封装：将数据和操作封装在类中，对外隐藏实现细节；\n继承：子类复用父类的属性和方法，并可扩展；\n多态：同一接口在不同对象上表现出不同行为（如方法重写、重载）。",
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

    // ---- 导入 ----

    private fun importFromUri(uri: Uri) {
        setBusy(true)
        binding.tvResult.visibility = View.GONE
        binding.tvFileName.text = uri.lastPathSegment ?: ""

        lifecycleScope.launch {
            try {
                // ---- 0. 读取文件并解析 JSON（尚未写入数据库） ----
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("无法读取文件")
                }

                val data = withContext(Dispatchers.Default) {
                    JsonImporter.parse(json)
                }

                // ---- 1. 查询本机现有题量，用于清空警示 ----
                val localQuestions = withContext(Dispatchers.IO) {
                    dao.getAllSubjectsWithCounts().sumOf { it.questionCount }
                }

                setBusy(false)

                // ---- 2. 弹出选择框：合并导入 / 清空后导入 ----
                val choiceBinding = DialogImportChoiceBinding.inflate(layoutInflater)
                choiceBinding.tvImportSummary.text =
                    getString(R.string.msg_import_choose_mode, data.questions.size)

                val choiceDialog = AlertDialog.Builder(this@ImportExportActivity)
                    .setTitle(R.string.btn_import)
                    .setView(choiceBinding.root)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()

                choiceBinding.optionMerge.setOnClickListener {
                    choiceDialog.dismiss()
                    applyImport(data, clearFirst = false)
                }
                choiceBinding.optionClear.setOnClickListener {
                    choiceDialog.dismiss()
                    confirmClearAndImport(data, localQuestions, data.questions.size)
                }
                choiceDialog.show()

            } catch (e: Exception) {
                showResult(false, getString(R.string.msg_import_failed, e.message))
                setBusy(false)
            }
        }
    }

    /** 清空操作不可逆，二次确认后再执行清空式导入 */
    private fun confirmClearAndImport(
        data: JsonImporter.ImportData,
        localQuestions: Int,
        fileQuestions: Int
    ) {
        AlertDialog.Builder(this)
            .setTitle("清空后导入")
            .setMessage(
                getString(R.string.msg_import_clear_confirm, localQuestions, fileQuestions)
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_confirm_clear_import) { _, _ ->
                applyImport(data, clearFirst = true)
            }
            .show()
    }

    /** 执行导入：合并（clearFirst=false）或清空后导入（clearFirst=true） */
    private fun applyImport(data: JsonImporter.ImportData, clearFirst: Boolean) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 清空模式：解析已成功，安全清空本机全部数据
                    if (clearFirst) {
                        dao.clearAllData()
                    }

                    suspend fun resolveSubject(name: String): Subject {
                        dao.findSubjectByName(name)?.let { return it }
                        val id = dao.insertSubject(Subject(name = name))
                        return Subject(id = id, name = name)
                    }

                    suspend fun resolveChapter(subject: Subject, name: String): Chapter {
                        dao.findChapter(subject.id, name)?.let { return it }
                        val id = dao.insertChapter(Chapter(subjectId = subject.id, name = name))
                        return Chapter(id = id, subjectId = subject.id, name = name)
                    }

                    val resolvedQuestions = data.questions.mapIndexed { index, q ->
                        val subjectName = q.subjectName ?: data.subjectName
                            ?: throw IllegalArgumentException(
                                "第 ${index + 1} 题缺少科目信息：请在 JSON 顶层或该题中填写 subject 字段。"
                            )
                        val chapterName = q.chapterName ?: data.defaultChapterName
                            ?: throw IllegalArgumentException(
                                "第 ${index + 1} 题缺少章节信息：请在 JSON 顶层或该题中填写 chapter 字段。"
                            )
                        val subject = resolveSubject(subjectName)
                        val chapter = resolveChapter(subject, chapterName)
                        q.copy(chapterId = chapter.id)
                    }

                    dao.insertBankWithQuestions(data.bank, resolvedQuestions)
                }

                val msgRes = if (clearFirst) R.string.msg_import_success_clear
                    else R.string.msg_import_success
                val msg = getString(msgRes, data.questions.size)
                showResult(true, msg)
                Toast.makeText(this@ImportExportActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showResult(false, getString(R.string.msg_import_failed, e.message))
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- 小工具 ----

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSelectFile.isEnabled = !busy
        binding.btnDownloadTemplate.isEnabled = !busy
        binding.btnExport.isEnabled = !busy
    }

    private fun showResult(ok: Boolean, msg: String) {
        binding.tvResult.text = msg
        binding.tvResult.setTextColor(getColor(if (ok) R.color.correct else R.color.wrong))
        binding.tvResult.visibility = View.VISIBLE
    }
}
