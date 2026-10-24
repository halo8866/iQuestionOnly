package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.Chapter
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.data.QuestionWithChapter
import com.iquestion.app.databinding.ActivityQuestionManageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 科目题目管理页：展示科目下所有题目（小字显示章节），支持单题删除、
 * 长按进入批量选择模式进行批量删除，点击题目进入编辑页。
 * 支持按章节、是否有效筛选。
 */
class QuestionManageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"

        /** 筛选状态 Spinner 选项编码 */
        private const val FILTER_ALL = 0
        private const val FILTER_VALID = 1
        private const val FILTER_INVALID = 2
    }

    private lateinit var binding: ActivityQuestionManageBinding
    private lateinit var dao: QuestionDao
    private var subjectId: Long = -1
    private val questions = mutableListOf<QuestionWithChapter>()
    private val selectedIds = linkedSetOf<Long>()
    private lateinit var adapter: QuestionAdapter
    private var selectionMode = false

    // 筛选状态
    private var chapters: List<Chapter> = emptyList()
    private var filterChapterPos = 0 // 0 = 全部章节
    private var filterValidPos = 0   // 0 = 全部, 1 = 仅有效, 2 = 仅无效

    private val chapterFilterListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            filterChapterPos = position
            applyFilter()
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private val validFilterListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            filterValidPos = position
            applyFilter()
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuestionManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)
        if (subjectId <= 0) {
            finish()
            return
        }

        dao = AppDatabase.getInstance(this).questionDao()

        adapter = QuestionAdapter(questions, selectedIds,
            onItemClick = { q -> onItemClicked(q) },
            onItemLongClick = { q -> enterSelectionMode(q.id) },
            onDeleteOne = { q -> confirmDeleteOne(q) }
        )
        binding.rvQuestions.layoutManager = LinearLayoutManager(this)
        binding.rvQuestions.adapter = adapter

        binding.btnCancelSelect.setOnClickListener { exitSelectionMode() }
        binding.btnSelectAll.setOnClickListener { selectAll() }
        binding.btnMarkValid.setOnClickListener { applyBatchValid(true) }
        binding.btnMarkInvalid.setOnClickListener { applyBatchValid(false) }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.toolbar.setNavigationOnClickListener {
            if (selectionMode) exitSelectionMode() else finish()
        }

        // 系统返回键：先退出选择模式
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) exitSelectionMode() else finish()
            }
        })

        setupFilters()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_question_manage, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 批量选择模式下隐藏右上角入口
        menu.findItem(R.id.action_batch_manage)?.isVisible = !selectionMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_batch_manage -> {
                enterSelectionMode()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        loadChaptersAndQuestions()
    }

    // ---- 筛选 ----

    private fun setupFilters() {
        binding.spFilterChapter.onItemSelectedListener = chapterFilterListener
        binding.spFilterValid.onItemSelectedListener = validFilterListener
    }

    /**
     * 加载章节列表（刷新下拉）并加载题目
     */
    private fun loadChaptersAndQuestions() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { dao.getChaptersBySubject(subjectId) }
            val prevChapterPos = filterChapterPos
            chapters = list

            // 移除监听器后再设置 adapter，避免回调时序问题
            binding.spFilterChapter.onItemSelectedListener = null
            val chapterNames = listOf(getString(R.string.filter_all_chapters)) + list.map { it.name }
            binding.spFilterChapter.adapter = ArrayAdapter(
                this@QuestionManageActivity, R.layout.item_spinner_filter, chapterNames
            ).apply { setDropDownViewResource(R.layout.item_spinner_filter_dropdown) }
            // 保持选中位置（不越界）
            filterChapterPos = if (prevChapterPos <= list.size) prevChapterPos else 0
            binding.spFilterChapter.setSelection(filterChapterPos)
            // 恢复监听器
            binding.spFilterChapter.onItemSelectedListener = chapterFilterListener

            // 有效性 Spinner（只需设置一次）
            if (binding.spFilterValid.adapter == null) {
                binding.spFilterValid.onItemSelectedListener = null
                val validNames = listOf(
                    getString(R.string.filter_all_status),
                    getString(R.string.filter_valid_only),
                    getString(R.string.filter_invalid_only)
                )
                binding.spFilterValid.adapter = ArrayAdapter(
                    this@QuestionManageActivity, R.layout.item_spinner_filter, validNames
                ).apply { setDropDownViewResource(R.layout.item_spinner_filter_dropdown) }
                binding.spFilterValid.setSelection(filterValidPos)
                binding.spFilterValid.onItemSelectedListener = validFilterListener
            }

            applyFilter()
        }
    }

    /**
     * 按章节 + 有效性筛选，从数据库加载并刷新列表
     */
    private fun applyFilter() {
        lifecycleScope.launch {
            val all: List<QuestionWithChapter> = withContext(Dispatchers.IO) {
                // 章节筛选：0 = 全部章节，>0 对应 chapters[Pos-1]
                val chapterId = if (filterChapterPos > 0 && filterChapterPos - 1 < chapters.size) {
                    chapters[filterChapterPos - 1].id
                } else {
                    -1L
                }
                val list = if (chapterId > 0) {
                    dao.getSubjectQuestionsByChapter(subjectId, chapterId)
                } else {
                    dao.getSubjectQuestions(subjectId)
                }
                // 有效性筛选（内存过滤，避免多查）
                val filtered = when (filterValidPos) {
                    FILTER_VALID -> list.filter { it.isValid }
                    FILTER_INVALID -> list.filter { !it.isValid }
                    else -> list
                }
                // 无效题目置底（稳定排序，同组内保持原有顺序）
                filtered.sortedBy { if (it.isValid) 0 else 1 }
            }
            questions.clear()
            questions.addAll(all)
            // 清除已不存在的选中项
            selectedIds.retainAll(questions.map { it.id }.toSet())
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (questions.isEmpty()) View.VISIBLE else View.GONE
            updateSelectionUi()
        }
    }

    private fun onItemClicked(q: QuestionWithChapter) {
        if (selectionMode) {
            toggleSelect(q.id)
        } else {
            val intent = Intent(this, EditQuestionActivity::class.java).apply {
                putExtra(EditQuestionActivity.EXTRA_QUESTION_ID, q.id)
                putExtra(EditQuestionActivity.EXTRA_SUBJECT_ID, subjectId)
            }
            startActivity(intent)
        }
    }

    // ---- 选择模式 ----

    private fun enterSelectionMode(firstId: Long? = null) {
        if (selectionMode) return
        selectionMode = true
        firstId?.let { selectedIds.add(it) }
        adapter.selectionMode = true
        updateSelectionUi()
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        adapter.selectionMode = false
        updateSelectionUi()
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        updateSelectionUi()
        adapter.notifyItemChanged(questions.indexOfFirst { it.id == id })
    }

    private fun selectAll() {
        if (selectedIds.size == questions.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            questions.forEach { selectedIds.add(it.id) }
        }
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.toolbar.title = if (selectionMode) {
            getString(R.string.selection_title_format, selectedIds.size)
        } else {
            getString(R.string.title_question_manage)
        }
    }

    // ---- 删除 ----

    private fun confirmDeleteOne(q: QuestionWithChapter) {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_question_manage)
            .setMessage(R.string.msg_delete_question_single)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteQuestionById(q.id) }
                    Toast.makeText(this@QuestionManageActivity, R.string.msg_update_success, Toast.LENGTH_SHORT).show()
                    applyFilter()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.msg_select_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete_selected)
            .setMessage(getString(R.string.msg_delete_questions_confirm, count))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteQuestionsByIds(selectedIds.toList()) }
                    Toast.makeText(
                        this@QuestionManageActivity,
                        getString(R.string.msg_deleted, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                    applyFilter()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 批量设置有效 / 无效（仅改状态，不删除）----

    private fun applyBatchValid(valid: Boolean) {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.msg_select_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedIds.size
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.setQuestionsValid(selectedIds.toList(), valid) }
            Toast.makeText(
                this@QuestionManageActivity,
                getString(
                    if (valid) R.string.msg_questions_set_valid else R.string.msg_questions_set_invalid,
                    count
                ),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            applyFilter()
        }
    }

    // ---- 列表适配器 ----

    class QuestionAdapter(
        private val items: List<QuestionWithChapter>,
        private val selectedIds: Set<Long>,
        private val onItemClick: (QuestionWithChapter) -> Unit,
        private val onItemLongClick: (QuestionWithChapter) -> Unit,
        private val onDeleteOne: (QuestionWithChapter) -> Unit
    ) : RecyclerView.Adapter<QuestionAdapter.VH>() {

        /** 是否处于批量选择模式（由 Activity 维护） */
        var selectionMode: Boolean = false

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
            val tvTypeTag: TextView = view.findViewById(R.id.tvTypeTag)
            val tvChapterTag: TextView = view.findViewById(R.id.tvChapterTag)
            val tvInvalidTag: TextView = view.findViewById(R.id.tvInvalidTag)
            val tvQuestionText: TextView = view.findViewById(R.id.tvQuestionText)
            val btnDeleteOne: MaterialButton = view.findViewById(R.id.btnDeleteOne)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_question_manage, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val q = items[position]
            val ctx = holder.itemView.context
            holder.tvTypeTag.text = when (q.type) {
                "single" -> ctx.getString(R.string.type_single)
                "multiple" -> ctx.getString(R.string.type_multiple)
                "judge" -> ctx.getString(R.string.type_judge)
                "essay" -> ctx.getString(R.string.type_essay)
                else -> q.type
            }
            // 小字显示章节
            holder.tvChapterTag.text = q.chapterName
            // 无效标记：显示「无效」标签并整体置灰
            holder.tvInvalidTag.visibility = if (q.isValid) View.GONE else View.VISIBLE
            holder.itemView.alpha = if (q.isValid) 1.0f else 0.55f
            holder.tvQuestionText.text = q.question

            val inSelectMode = selectionMode
            holder.cbSelect.visibility = if (inSelectMode) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = selectedIds.contains(q.id)
            // 选择模式下隐藏单题删除按钮，避免误触
            holder.btnDeleteOne.visibility = if (inSelectMode) View.GONE else View.VISIBLE

            holder.itemView.setOnClickListener { onItemClick(q) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(q)
                true
            }
            holder.btnDeleteOne.setOnClickListener { onDeleteOne(q) }
        }

        override fun getItemCount() = items.size
    }
}
