package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.Chapter
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.data.WrongQuestionItem
import com.iquestion.app.databinding.ActivityWrongBookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 科目错题本：展示该科目所有错题（含上次错误作答），
 * 支持按章节筛选、按关键词搜索（题目/答案/解析），支持一键错题练习。
 * 练习中答对会自动从错题本消除。
 */
class WrongBookActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"
    }

    private lateinit var binding: ActivityWrongBookBinding
    private lateinit var dao: QuestionDao
    private var subjectId: Long = -1

    // 全量错题（有效在前、无效置底）；displayList 为筛选/搜索后的展示列表
    private val allWrong = mutableListOf<WrongQuestionItem>()
    private val displayList = mutableListOf<WrongQuestionItem>()
    private lateinit var adapter: WrongAdapter

    // 筛选状态
    private var chapters: List<Chapter> = emptyList()
    private var filterChapterPos = 0 // 0 = 全部章节
    private var searchKeyword = ""

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val chapterFilterListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            filterChapterPos = position
            applyFilter()
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWrongBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)
        if (subjectId <= 0) {
            finish()
            return
        }

        dao = AppDatabase.getInstance(this).questionDao()

        adapter = WrongAdapter(displayList, timeFormat)
        binding.rvWrong.layoutManager = LinearLayoutManager(this)
        binding.rvWrong.adapter = adapter

        binding.btnStartWrongPractice.setOnClickListener { startWrongPractice() }
        binding.spFilterChapter.onItemSelectedListener = chapterFilterListener
        setupSearch()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchKeyword = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
    }

    private fun loadData() {
        lifecycleScope.launch {
            val chapterList = withContext(Dispatchers.IO) { dao.getChaptersBySubject(subjectId) }
            val wrong = withContext(Dispatchers.IO) { dao.getWrongQuestionsBySubject(subjectId) }
            chapters = chapterList

            // 章节下拉（移除监听后设置，避免回调时序问题）
            binding.spFilterChapter.onItemSelectedListener = null
            val chapterNames = listOf(getString(R.string.filter_all_chapters)) + chapterList.map { it.name }
            binding.spFilterChapter.adapter = ArrayAdapter(
                this@WrongBookActivity, R.layout.item_spinner_filter, chapterNames
            ).apply { setDropDownViewResource(R.layout.item_spinner_filter_dropdown) }
            filterChapterPos = filterChapterPos.coerceAtMost(chapterList.size)
            binding.spFilterChapter.setSelection(filterChapterPos)
            binding.spFilterChapter.onItemSelectedListener = chapterFilterListener

            allWrong.clear()
            allWrong.addAll(wrong)
            applyFilter()
        }
    }

    /**
     * 按章节 + 关键词筛选，刷新展示列表与练习按钮
     */
    private fun applyFilter() {
        val kw = searchKeyword.lowercase(Locale.getDefault())
        val chapterId = if (filterChapterPos > 0 && filterChapterPos - 1 < chapters.size) {
            chapters[filterChapterPos - 1].id
        } else {
            -1L
        }

        val filtered = allWrong.filter { item ->
            val chapterOk = chapterId <= 0 || item.chapterId == chapterId
            val keywordOk = kw.isEmpty() ||
                item.question.lowercase(Locale.getDefault()).contains(kw) ||
                item.answer.lowercase(Locale.getDefault()).contains(kw) ||
                (item.analysis?.lowercase(Locale.getDefault())?.contains(kw) == true)
            chapterOk && keywordOk
        }

        displayList.clear()
        displayList.addAll(filtered)
        adapter.notifyDataSetChanged()

        // 练习数 = 当前筛选结果中的有效错题
        val practiceCount = displayList.count { it.isValid }
        binding.btnStartWrongPractice.isEnabled = practiceCount > 0
        binding.btnStartWrongPractice.text = getString(R.string.btn_start_wrong_format, practiceCount)

        // 空状态文案区分：科目无错题 vs 筛选/搜索无结果
        if (allWrong.isEmpty()) {
            binding.tvEmpty.text = getString(R.string.hint_no_wrong_questions)
            binding.tvEmpty.visibility = View.VISIBLE
        } else if (displayList.isEmpty()) {
            binding.tvEmpty.text = getString(R.string.hint_wrong_no_filter_result)
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.tvEmpty.visibility = View.GONE
        }
    }

    /**
     * 错题练习：复用练习页，题目来源为当前筛选结果中的有效错题；答对自动消除
     */
    private fun startWrongPractice() {
        val validIds = displayList.filter { it.isValid }.map { it.id }
        if (validIds.isEmpty()) {
            Toast.makeText(this, R.string.hint_no_wrong_questions, Toast.LENGTH_SHORT).show()
            return
        }
        val subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: ""
        val intent = Intent(this, PracticeActivity::class.java).apply {
            putExtra(PracticeActivity.EXTRA_WRONG_SUBJECT_ID, subjectId)
            putExtra(PracticeActivity.EXTRA_WRONG_QUESTION_IDS, ArrayList(validIds))
            putExtra(PracticeActivity.EXTRA_CHAPTER_NAME, "${subjectName} · ${getString(R.string.title_wrong_practice)}")
            putExtra(PracticeActivity.EXTRA_MODE, PracticeActivity.MODE_SEQUENCE)
        }
        startActivity(intent)
    }

    class WrongAdapter(
        private val items: List<WrongQuestionItem>,
        private val timeFormat: SimpleDateFormat
    ) : RecyclerView.Adapter<WrongAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTypeTag: TextView = view.findViewById(R.id.tvTypeTag)
            val tvInvalidTag: TextView = view.findViewById(R.id.tvInvalidTag)
            val tvChapterTag: TextView = view.findViewById(R.id.tvChapterTag)
            val tvWrongTime: TextView = view.findViewById(R.id.tvWrongTime)
            val tvQuestionText: TextView = view.findViewById(R.id.tvQuestionText)
            val tvWrongAnswer: TextView = view.findViewById(R.id.tvWrongAnswer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wrong_question, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.tvTypeTag.text = when (item.type) {
                "single" -> ctx.getString(R.string.type_single)
                "multiple" -> ctx.getString(R.string.type_multiple)
                "judge" -> ctx.getString(R.string.type_judge)
                "essay" -> ctx.getString(R.string.type_essay)
                else -> item.type
            }
            // 无效错题：显示「无效」徽章并整卡置灰（RecyclerView 复用，需双向重置）
            val invalid = !item.isValid
            holder.tvInvalidTag.visibility = if (invalid) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (invalid) 0.55f else 1.0f
            holder.tvChapterTag.text = item.chapterName
            holder.tvWrongTime.text = timeFormat.format(Date(item.wrongAt))
            holder.tvQuestionText.text = item.question
            holder.tvWrongAnswer.text =
                ctx.getString(R.string.label_last_wrong_answer, item.wrongAnswer)
        }

        override fun getItemCount() = items.size
    }
}
