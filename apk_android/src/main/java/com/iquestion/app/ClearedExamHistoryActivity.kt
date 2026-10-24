package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.ExamRecord
import com.iquestion.app.databinding.ActivityClearedExamHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已清空的历史：展示所有被删除（软删除 isValid=0）的考试记录。
 * 数据仍保留在库中，点击仍可查看该次考试的试卷详情，长按可恢复。
 */
class ClearedExamHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClearedExamHistoryBinding
    private val records = mutableListOf<ExamRecord>()
    private lateinit var adapter: HistoryAdapter
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClearedExamHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = HistoryAdapter(
            records = records,
            timeFormat = timeFormat,
            onClick = { record -> openDetail(record) },
            onLongClick = { record -> confirmRestore(record) }
        )
        binding.rvCleared.layoutManager = LinearLayoutManager(this)
        binding.rvCleared.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@ClearedExamHistoryActivity).questionDao()
                    .getClearedExamRecords()
            }
            records.clear()
            records.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openDetail(record: ExamRecord) {
        val intent = Intent(this, ExamHistoryDetailActivity::class.java).apply {
            putExtra(ExamHistoryActivity.EXTRA_RECORD_ID, record.id)
            putExtra(ExamHistoryActivity.EXTRA_RECORD_TITLE, record.title)
        }
        startActivity(intent)
    }

    /**
     * 长按恢复：把该记录的 isValid 置回 1，重新显示在考试管理中
     */
    private fun confirmRestore(record: ExamRecord) {
        AlertDialog.Builder(this)
            .setTitle(record.title)
            .setMessage(R.string.msg_restore_history_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(this@ClearedExamHistoryActivity).questionDao()
                            .restoreExamRecord(record.id)
                    }
                    Toast.makeText(this@ClearedExamHistoryActivity, R.string.msg_history_restored, Toast.LENGTH_SHORT).show()
                    loadRecords()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 列表适配器（沿用原考试历史列表样式）----

    class HistoryAdapter(
        private val records: List<ExamRecord>,
        private val timeFormat: SimpleDateFormat,
        private val onClick: (ExamRecord) -> Unit,
        private val onLongClick: (ExamRecord) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvRecordTitle)
            val tvTime: TextView = view.findViewById(R.id.tvRecordTime)
            val tvScore: TextView = view.findViewById(R.id.tvRecordScore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_exam_record, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = records[position]
            val ctx = holder.itemView.context
            holder.tvTitle.text = record.title
            holder.tvTime.text = timeFormat.format(Date(record.createdAt))
            val rate = if (record.totalCount > 0) {
                record.correctCount * 100.0 / record.totalCount
            } else 0.0
            holder.tvScore.text = ctx.getString(
                R.string.exam_score_format,
                record.correctCount, record.totalCount, rate
            )
            holder.tvScore.setTextColor(
                ctx.getColor(if (rate >= 60) R.color.correct else R.color.wrong)
            )
            holder.itemView.setOnClickListener { onClick(record) }
            holder.itemView.setOnLongClickListener {
                onLongClick(record)
                true
            }
        }

        override fun getItemCount() = records.size
    }
}
