package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.Chapter
import com.iquestion.app.data.ChapterWithCounts
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.databinding.ActivitySubjectBinding
import com.iquestion.app.databinding.DialogSubjectExamBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 科目详情页：展示该科目下的章节（总题数/已做题数），支持手动添加/删除章节；
 * 点击章节弹出练习方式选择（顺序练习 / 乱序练习）；
 * 顶部菜单科目考试：先多选考试章节，再设置题型数量后开考
 */
class SubjectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"

        /** 考试时长选项（分钟数），0 表示不限时，-1 表示自定义 */
        val DURATION_OPTIONS = intArrayOf(0, 30, 60, 90, 120, 180, -1)
        const val DURATION_CUSTOM = -1
        const val DEFAULT_DURATION_INDEX = 2 // 默认 1 小时（60 分钟）
    }

    private lateinit var binding: ActivitySubjectBinding
    private lateinit var dao: QuestionDao
    private var subjectId: Long = -1
    private val chapters = mutableListOf<ChapterWithCounts>()
    private lateinit var adapter: ChapterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubjectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)
        val subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: getString(R.string.title_chapters)
        binding.toolbar.title = subjectName

        if (subjectId <= 0) {
            finish()
            return
        }

        dao = AppDatabase.getInstance(this).questionDao()

        adapter = ChapterAdapter(chapters,
            onChapterClick = { chapter -> showPracticeModeDialog(chapter) },
            onDelete = { chapter -> confirmDeleteChapter(chapter) }
        )
        binding.rvChapters.layoutManager = LinearLayoutManager(this)
        binding.rvChapters.adapter = adapter

        binding.fabAddChapter.setOnClickListener { showAddChapterDialog() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_subject, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_subject_exam -> {
                checkDraftAndStartExam()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 科目考试入口：若存在本科目的暂存考试，先询问「恢复原有考试 / 新开考试」；
     * 选择任一项都会清除暂存，取消则保留暂存供下次使用。
     */
    private fun checkDraftAndStartExam() {
        val draft = com.iquestion.app.util.ExamDraftStore.load(this)
        if (draft != null && draft.subjectId == subjectId) {
            val total = draft.questionIds.size
            val done = draft.userSelections.filter { it.value.isNotEmpty() }.size
            val minutes = draft.durationMinutes
            val msg = buildString {
                append(getString(R.string.msg_draft_resume_message, draft.subjectName, total, done))
                if (minutes > 0) {
                    append(getString(R.string.msg_draft_resume_duration, minutes))
                }
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.msg_draft_resume_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.action_resume_draft) { _, _ ->
                    // 恢复原有考试：草稿在交卷时由 PracticeActivity 清除
                    val intent = Intent(this, PracticeActivity::class.java).apply {
                        putExtra(PracticeActivity.EXTRA_SUBJECT_EXAM_ID, subjectId)
                        putExtra(PracticeActivity.EXTRA_CHAPTER_NAME, draft.subjectName)
                        putExtra(PracticeActivity.EXTRA_MODE, PracticeActivity.MODE_EXAM)
                        putExtra(PracticeActivity.EXTRA_EXAM_DURATION_MINUTES, draft.durationMinutes)
                        putExtra(PracticeActivity.EXTRA_RESUME_DRAFT, true)
                    }
                    startActivity(intent)
                }
                .setNeutralButton(R.string.action_new_exam) { _, _ ->
                    com.iquestion.app.util.ExamDraftStore.clear(this)
                    showExamChapterPicker()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            // 其他科目的草稿或无草稿：直接开始本次考试
            showExamChapterPicker()
        }
    }

    /**
     * 科目考试第一步：多选本次考试包含的章节（默认全选），下一步进入题型数量设置
     */
    private fun showExamChapterPicker() {
        if (chapters.isEmpty()) {
            showSubjectExamDialog(null)
            return
        }
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }
        val checkBoxes = chapters.map { chapter ->
            MaterialCheckBox(this).apply {
                text = getString(
                    R.string.exam_chapter_item_format, chapter.name, chapter.questionCount
                )
                isChecked = true
                textSize = 15f
            }.also { container.addView(it) }
        }
        val scrollView = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.title_select_exam_chapters)
            .setView(scrollView)
            .setPositiveButton(R.string.btn_pick_chapters_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // 拦截确定按钮：至少勾选一个章节才放行进入下一步
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checkBoxes.mapIndexed { i, cb ->
                    if (cb.isChecked) chapters[i].id else null
                }.filterNotNull().toLongArray()
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.msg_select_at_least_one_chapter, Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    showSubjectExamDialog(selected)
                }
            }
        }
        dialog.show()
    }

    /**
     * 科目考试设置弹窗：选择考试时长 + 各题型数量后开始考试（章节范围由上一步决定）
     */
    private fun showSubjectExamDialog(selectedChapterIds: LongArray?) {
        val dialogBinding = DialogSubjectExamBinding.inflate(layoutInflater)

        // 考试时长下拉框：不限时 / 30 / 60 / 90 / 120 / 180 / 自定义 分钟，默认 1 小时
        val durationLabels = DURATION_OPTIONS.map { minutes ->
            when (minutes) {
                0 -> getString(R.string.duration_no_limit)
                30 -> getString(R.string.duration_30min)
                60 -> getString(R.string.duration_60min)
                90 -> getString(R.string.duration_90min)
                120 -> getString(R.string.duration_120min)
                180 -> getString(R.string.duration_180min)
                else -> getString(R.string.duration_custom)
            }
        }
        dialogBinding.spinnerDuration.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, durationLabels
        )
        dialogBinding.spinnerDuration.setSelection(DEFAULT_DURATION_INDEX)

        // 选中「自定义」时立即弹出分钟数输入框；结果通过闭包变量带回并回显
        var customMinutes: Int? = null
        dialogBinding.spinnerDuration.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    if (DURATION_OPTIONS[position] == DURATION_CUSTOM) {
                        showCustomDurationDialog { minutes ->
                            customMinutes = minutes
                            dialogBinding.tvCustomDuration.text =
                                getString(R.string.label_custom_duration_value, minutes)
                            dialogBinding.tvCustomDuration.visibility = View.VISIBLE
                        }
                    } else {
                        customMinutes = null
                        dialogBinding.tvCustomDuration.visibility = View.GONE
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_subject_exam)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val single = dialogBinding.etCountSingle.text.toString().toIntOrNull() ?: 0
                val multiple = dialogBinding.etCountMultiple.text.toString().toIntOrNull() ?: 0
                val judge = dialogBinding.etCountJudge.text.toString().toIntOrNull() ?: 0
                val essay = dialogBinding.etCountEssay.text.toString().toIntOrNull() ?: 0
                val total = single + multiple + judge + essay
                if (total == 0) {
                    Toast.makeText(this, R.string.msg_exam_count_zero, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selectedOption = DURATION_OPTIONS[dialogBinding.spinnerDuration.selectedItemPosition]
                val durationMinutes = if (selectedOption == DURATION_CUSTOM) {
                    customMinutes ?: 0 // 未输入则按不限时处理
                } else {
                    selectedOption
                }
                startSubjectExam(single, multiple, judge, essay, durationMinutes, selectedChapterIds)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 自定义考试时长输入框：要求输入 1~9999 之间的分钟数
     */
    private fun showCustomDurationDialog(onResult: (Int) -> Unit) {
        val editText = EditText(this).apply {
            hint = getString(R.string.hint_custom_duration)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 16)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.title_custom_duration)
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = editText.text.toString().toIntOrNull()
                if (value == null || value < 1 || value > 9999) {
                    Toast.makeText(this, R.string.msg_invalid_duration, Toast.LENGTH_SHORT).show()
                } else {
                    onResult(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun startSubjectExam(
        single: Int,
        multiple: Int,
        judge: Int,
        essay: Int,
        durationMinutes: Int,
        selectedChapterIds: LongArray?
    ) {
        val subjectName = binding.toolbar.title.toString()
        val intent = Intent(this, PracticeActivity::class.java).apply {
            putExtra(PracticeActivity.EXTRA_SUBJECT_EXAM_ID, subjectId)
            putExtra(PracticeActivity.EXTRA_CHAPTER_NAME, subjectName)
            putExtra(PracticeActivity.EXTRA_MODE, PracticeActivity.MODE_EXAM)
            putExtra(PracticeActivity.EXTRA_EXAM_COUNT_SINGLE, single)
            putExtra(PracticeActivity.EXTRA_EXAM_COUNT_MULTIPLE, multiple)
            putExtra(PracticeActivity.EXTRA_EXAM_COUNT_JUDGE, judge)
            putExtra(PracticeActivity.EXTRA_EXAM_COUNT_ESSAY, essay)
            putExtra(PracticeActivity.EXTRA_EXAM_DURATION_MINUTES, durationMinutes)
            if (selectedChapterIds != null && selectedChapterIds.isNotEmpty()) {
                putExtra(PracticeActivity.EXTRA_EXAM_CHAPTER_IDS, selectedChapterIds)
            }
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        loadChapters()
    }

    private fun loadChapters() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { dao.getChaptersWithCounts(subjectId) }
            chapters.clear()
            chapters.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (chapters.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * 手动添加章节（名称自定义，如「第一章」「数据库」）
     */
    private fun showAddChapterDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.hint_input_chapter)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_add_chapter)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.msg_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addChapter(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addChapter(name: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = dao.findChapter(subjectId, name)
                    if (existing != null) {
                        throw IllegalArgumentException(getString(R.string.msg_chapter_exists))
                    }
                    dao.insertChapter(Chapter(subjectId = subjectId, name = name))
                }
                loadChapters()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SubjectActivity,
                    getString(R.string.msg_add_failed, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmDeleteChapter(chapter: ChapterWithCounts) {
        AlertDialog.Builder(this)
            .setTitle(chapter.name)
            .setMessage(R.string.msg_delete_chapter_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteChapter(chapter.id) }
                    loadChapters()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 点击章节：弹出练习方式选择（顺序练习 / 乱序练习）
     */
    private fun showPracticeModeDialog(chapter: ChapterWithCounts) {
        if (chapter.questionCount == 0) {
            Toast.makeText(this, R.string.hint_no_questions, Toast.LENGTH_SHORT).show()
            return
        }
        val modes = arrayOf(
            getString(R.string.btn_sequence_practice),
            getString(R.string.btn_random_practice)
        )
        AlertDialog.Builder(this)
            .setTitle(chapter.name)
            .setItems(modes) { _, which ->
                startPractice(
                    chapter,
                    if (which == 0) PracticeActivity.MODE_SEQUENCE else PracticeActivity.MODE_RANDOM
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPractice(chapter: ChapterWithCounts, mode: Int) {
        if (chapter.questionCount == 0) {
            Toast.makeText(this, R.string.hint_no_questions, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PracticeActivity::class.java).apply {
            putExtra(PracticeActivity.EXTRA_CHAPTER_ID, chapter.id)
            putExtra(PracticeActivity.EXTRA_CHAPTER_NAME, chapter.name)
            putExtra(PracticeActivity.EXTRA_MODE, mode)
        }
        startActivity(intent)
    }

    class ChapterAdapter(
        private val items: List<ChapterWithCounts>,
        private val onChapterClick: (ChapterWithCounts) -> Unit,
        private val onDelete: (ChapterWithCounts) -> Unit
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvBankName)
            val tvCount: TextView = view.findViewById(R.id.tvCount)
            val tvDesc: TextView = view.findViewById(R.id.tvDescription)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_question_bank, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val chapter = items[position]
            holder.tvName.text = chapter.name
            holder.tvCount.text =
                holder.itemView.context.getString(
                    R.string.chapter_progress_format,
                    chapter.questionCount, chapter.doneCount
                )
            holder.tvDesc.visibility = View.GONE
            holder.itemView.setOnClickListener { onChapterClick(chapter) }
            holder.btnDelete.setOnClickListener { onDelete(chapter) }
        }

        override fun getItemCount() = items.size
    }
}
