package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.ExamRecord
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.databinding.ActivityExamHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 考试管理：以卡片列出历次考试记录（时间小字 + 考试名称 + 成绩），支持：
 * - 点击记录查看试卷详情
 * - 长按某条记录：真实删除（连同作答快照一并物理删除，不可恢复），有二次确认
 * - 有效/无效标志：仅通过右上角「批量管理」批量设置，只改保存状态、不删除数据；
 *   无效记录在列表中以「无效」徽标 + 置灰显示，仍可点击查看详情。
 */
class ExamHistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_RECORD_TITLE = "record_title"
    }

    private lateinit var binding: ActivityExamHistoryBinding
    private lateinit var dao: QuestionDao
    private val records = mutableListOf<ExamRecord>()
    private val selectedIds = linkedSetOf<Long>()
    private lateinit var adapter: ExamManageAdapter
    private var selectionMode = false
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dao = AppDatabase.getInstance(this).questionDao()

        adapter = ExamManageAdapter(
            records = records,
            timeFormat = timeFormat,
            selectedIds = selectedIds,
            onItemClick = { record -> onItemClicked(record) },
            onItemLongClick = { record -> enterSelectionMode(record.id) }
        )
        binding.rvExams.layoutManager = LinearLayoutManager(this)
        binding.rvExams.adapter = adapter

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
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_exam_history, menu)
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
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                // 无效记录置底（稳定排序，同组内保持时间倒序）
                dao.getExamRecords().sortedBy { if (it.isValid) 0 else 1 }
            }
            records.clear()
            records.addAll(list)
            // 清除已不存在的选中项
            selectedIds.retainAll(records.map { it.id }.toSet())
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            updateSelectionUi()
        }
    }

    private fun onItemClicked(record: ExamRecord) {
        if (selectionMode) {
            toggleSelect(record.id)
        } else {
            val intent = Intent(this, ExamHistoryDetailActivity::class.java).apply {
                putExtra(EXTRA_RECORD_ID, record.id)
                putExtra(EXTRA_RECORD_TITLE, record.title)
            }
            startActivity(intent)
        }
    }

    // ---- 批量删除（物理删除记录与作答快照，不可恢复）----

    /** 批量删除选中的考试记录，二次确认后物理删除 */
    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.msg_select_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete_selected)
            .setMessage(getString(R.string.msg_exams_delete_permanent_confirm, count))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dao.deleteExamRecordsPhysically(selectedIds.toList())
                    }
                    Toast.makeText(
                        this@ExamHistoryActivity,
                        getString(R.string.msg_exams_deleted_permanent, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                    loadRecords()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 批量选择模式 ----

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
        adapter.notifyItemChanged(records.indexOfFirst { it.id == id })
    }

    private fun selectAll() {
        if (selectedIds.size == records.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            records.forEach { selectedIds.add(it.id) }
        }
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.toolbar.title = if (selectionMode) {
            getString(R.string.selection_title_exams_format, selectedIds.size)
        } else {
            getString(R.string.title_exam_history)
        }
    }

    /** 批量设置选中记录的有效/无效标志 */
    private fun applyBatchValid(valid: Boolean) {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.msg_select_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedIds.size
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.setExamRecordsValid(selectedIds.toList(), valid) }
            Toast.makeText(
                this@ExamHistoryActivity,
                getString(
                    if (valid) R.string.msg_exams_set_valid else R.string.msg_exams_set_invalid,
                    count
                ),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            loadRecords()
        }
    }

    // ---- 列表适配器（参考题目管理卡片样式）----

    class ExamManageAdapter(
        private val records: List<ExamRecord>,
        private val timeFormat: SimpleDateFormat,
        private val selectedIds: Set<Long>,
        private val onItemClick: (ExamRecord) -> Unit,
        private val onItemLongClick: (ExamRecord) -> Unit
    ) : RecyclerView.Adapter<ExamManageAdapter.VH>() {

        /** 是否处于批量选择模式（由 Activity 维护） */
        var selectionMode: Boolean = false

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
            val tvExamTime: TextView = view.findViewById(R.id.tvExamTime)
            val tvExamTitle: TextView = view.findViewById(R.id.tvExamTitle)
            val tvExamScore: TextView = view.findViewById(R.id.tvExamScore)
            val tvValidBadge: TextView = view.findViewById(R.id.tvValidBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_exam_manage, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = records[position]
            val ctx = holder.itemView.context
            holder.tvExamTime.text = timeFormat.format(Date(r.createdAt))
            holder.tvExamTitle.text = r.title
            val rate = if (r.totalCount > 0) r.correctCount * 100.0 / r.totalCount else 0.0
            holder.tvExamScore.text = ctx.getString(
                R.string.exam_score_format, r.correctCount, r.totalCount, rate
            )
            holder.tvExamScore.setTextColor(
                ctx.getColor(if (rate >= 60) R.color.correct else R.color.wrong)
            )

            // 无效记录：显示「无效」徽标并整体置灰
            holder.tvValidBadge.visibility = if (r.isValid) View.GONE else View.VISIBLE
            holder.itemView.alpha = if (r.isValid) 1.0f else 0.55f

            // 批量选择框（仅选择模式可见）
            holder.cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = selectedIds.contains(r.id)

            holder.itemView.setOnClickListener { onItemClick(r) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(r)
                true
            }
        }

        override fun getItemCount() = records.size
    }
}
