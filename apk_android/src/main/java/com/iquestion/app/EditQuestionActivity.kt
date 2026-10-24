package com.iquestion.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.Chapter
import com.iquestion.app.data.Question
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.databinding.ActivityEditQuestionBinding
import com.iquestion.app.util.JsonImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题目编辑页：可修改题型、题目内容、选项与正确答案（勾选）、参考答案（解答题）、解析、所属章节
 */
class EditQuestionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUESTION_ID = "question_id"
        const val EXTRA_SUBJECT_ID = "subject_id"

        /** Spinner 位置对应的题型编码 */
        private val TYPE_CODES = listOf("single", "multiple", "judge", "essay")
    }

    private lateinit var binding: ActivityEditQuestionBinding
    private lateinit var dao: QuestionDao
    private val gson = Gson()

    private var subjectId: Long = -1
    private var questionId: Long = -1
    private var question: Question? = null
    private var chapters: List<Chapter> = emptyList()

    /** 切换题型时暂存当前选项（文本, 是否勾选），避免误清空 */
    private var savedOptionState = mutableListOf<Pair<String, Boolean>>()

    private val typeSpinnerListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            rebuildOptionsArea(TYPE_CODES[position])
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditQuestionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)
        questionId = intent.getLongExtra(EXTRA_QUESTION_ID, -1)
        if (subjectId <= 0 || questionId <= 0) {
            finish()
            return
        }

        dao = AppDatabase.getInstance(this).questionDao()

        binding.btnAddOption.setOnClickListener { addOptionRow("", false, removable = true) }
        binding.btnSave.setOnClickListener { save() }

        lifecycleScope.launch {
            chapters = withContext(Dispatchers.IO) { dao.getChaptersBySubject(subjectId) }
            val q = withContext(Dispatchers.IO) { dao.getQuestionById(questionId) }
            if (q == null || chapters.isEmpty()) {
                Toast.makeText(this@EditQuestionActivity, R.string.hint_no_questions, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            question = q
            setupSpinners(q)
        }
    }

    private fun setupSpinners(q: Question) {
        // 章节 Spinner
        val chapterNames = chapters.map { it.name }
        binding.spChapter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, chapterNames
        )
        val chapterIndex = chapters.indexOfFirst { it.id == q.chapterId }
        if (chapterIndex >= 0) binding.spChapter.setSelection(chapterIndex)

        // 题型 Spinner（切换时重建选项区）
        val typeNames = listOf(
            getString(R.string.type_single),
            getString(R.string.type_multiple),
            getString(R.string.type_judge),
            getString(R.string.type_essay)
        )
        // 先移除监听器，设置 adapter 和选中项后再恢复，避免回调时序问题
        binding.spType.onItemSelectedListener = null
        binding.spType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, typeNames
        )
        val typeIndex = TYPE_CODES.indexOf(q.type).takeIf { it >= 0 } ?: 0
        binding.spType.setSelection(typeIndex)
        binding.spType.onItemSelectedListener = typeSpinnerListener

        // 题目/解析
        binding.etQuestion.setText(q.question)
        binding.etAnalysis.setText(q.analysis ?: "")

        // 有效/无效开关
        binding.swValid.isChecked = q.isValid

        // 初始化选项区（按已保存题型构建）
        rebuildOptionsArea(q.type, fromStored = true)
    }

    /**
     * 按题型重建选项区：
     * - 单选/多选：动态行，可增删，勾选行即正确答案
     * - 判断：固定两行（正确/错误，可编辑文本）
     * - 解答：隐藏选项，显示参考答案输入框
     */
    private fun rebuildOptionsArea(type: String, fromStored: Boolean = false) {
        val q = question
        val isEssay = type == "essay"

        binding.tvOptionsLabel.visibility = if (isEssay) View.GONE else View.VISIBLE
        binding.llOptions.visibility = if (isEssay) View.GONE else View.VISIBLE
        binding.btnAddOption.visibility =
            if (isEssay || type == "judge") View.GONE else View.VISIBLE
        binding.tvEssayAnswerLabel.visibility = if (isEssay) View.VISIBLE else View.GONE
        binding.etEssayAnswer.visibility = if (isEssay) View.VISIBLE else View.GONE

        if (isEssay) {
            if (fromStored && q != null) binding.etEssayAnswer.setText(q.answer)
            return
        }

        // 记录当前行状态，供切换回来时恢复
        val current = harvestRows()
        if (current.isNotEmpty()) {
            savedOptionState.clear()
            savedOptionState.addAll(current)
        }

        binding.llOptions.removeAllViews()

        when (type) {
            "judge" -> {
                val texts: List<String>
                var checkedIdx = -1
                if (fromStored && q != null) {
                    texts = JsonImporter.parseOptions(q.options).ifEmpty { listOf("正确", "错误") }
                    // 判断题 answer 为文本（正确/错误），按文本匹配勾选
                    checkedIdx = texts.indexOfFirst { it == q.answer }
                } else {
                    texts = savedOptionState.take(2).map { it.first }
                        .let { if (it.size >= 2) it else it + listOf("正确", "错误").drop(it.size) }
                    checkedIdx = savedOptionState.take(2).indexOfFirst { it.second }
                }
                texts.take(2).forEachIndexed { i, text ->
                    addOptionRow(text, i == checkedIdx, removable = false, exclusive = true)
                }
            }
            else -> { // single / multiple
                val state: List<Pair<String, Boolean>> = if (fromStored && q != null) {
                    val options = JsonImporter.parseOptions(q.options)
                    val answer = q.answer.uppercase()
                    options.mapIndexed { idx, text ->
                        val checked = when (q.type) {
                            "single" -> text.trimStart().uppercase().startsWith(answer)
                            "multiple" -> answer.split(",")
                                .any { text.trimStart().uppercase().startsWith(it.trim()) }
                            else -> false
                        }
                        text to checked
                    }.ifEmpty { List(4) { "" to false } }
                } else {
                    savedOptionState.ifEmpty { List(4) { "" to false } }
                }
                val exclusive = type == "single"
                state.forEach { (text, checked) ->
                    addOptionRow(text, checked, removable = true, exclusive = exclusive)
                }
                while (binding.llOptions.childCount < 2) {
                    addOptionRow("", false, removable = true, exclusive = exclusive)
                }
            }
        }
    }

    /**
     * 添加一行选项；exclusive=true（单选/判断）时勾选互斥
     */
    private fun addOptionRow(text: String, checked: Boolean, removable: Boolean, exclusive: Boolean = false) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_option_edit, binding.llOptions, false)
        val cb = row.findViewById<CheckBox>(R.id.cbCorrect)
        val et = row.findViewById<EditText>(R.id.etOptionText)
        val btnRemove = row.findViewById<TextView>(R.id.btnRemoveOption)

        et.setText(text)
        et.hint = getString(R.string.hint_option_text)
        btnRemove.visibility = if (removable) View.VISIBLE else View.GONE

        cb.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && exclusive) {
                // 互斥：取消其它行的勾选
                for (i in 0 until binding.llOptions.childCount) {
                    val other = binding.llOptions.getChildAt(i)
                    val otherCb = other.findViewById<CheckBox>(R.id.cbCorrect)
                    if (other !== buttonView.parent && otherCb.isChecked) {
                        otherCb.isChecked = false
                    }
                }
            }
        }
        cb.isChecked = checked

        btnRemove.setOnClickListener {
            if (binding.llOptions.childCount <= 2) {
                Toast.makeText(this, R.string.msg_need_two_options, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.llOptions.removeView(row)
        }

        binding.llOptions.addView(row)
    }

    /**
     * 收集当前选项行状态
     */
    private fun harvestRows(): List<Pair<String, Boolean>> {
        val result = mutableListOf<Pair<String, Boolean>>()
        for (i in 0 until binding.llOptions.childCount) {
            val row = binding.llOptions.getChildAt(i) as? LinearLayout ?: continue
            val cb = row.findViewById<CheckBox>(R.id.cbCorrect)
            val et = row.findViewById<EditText>(R.id.etOptionText)
            result.add(et.text.toString().trim() to cb.isChecked)
        }
        return result
    }

    private fun save() {
        val q = question ?: return

        val questionText = binding.etQuestion.text.toString().trim()
        if (questionText.isEmpty()) {
            toast(R.string.msg_question_empty)
            return
        }

        val type = TYPE_CODES.getOrElse(binding.spType.selectedItemPosition) { "single" }
        val rows = harvestRows()
        val analysis = binding.etAnalysis.text.toString().trim().ifEmpty { null }
        val chapterId = chapters.getOrNull(binding.spChapter.selectedItemPosition)?.id ?: q.chapterId

        val (optionsJson, answer) = when (type) {
            "essay" -> {
                val refAnswer = binding.etEssayAnswer.text.toString().trim()
                if (refAnswer.isEmpty()) {
                    toast(R.string.msg_essay_answer_empty)
                    return
                }
                "[]" to refAnswer
            }
            "judge" -> {
                if (rows.any { it.first.isEmpty() }) {
                    toast(R.string.msg_option_empty)
                    return
                }
                val checked = rows.filter { it.second }
                if (checked.size != 1) {
                    toast(R.string.msg_judge_one_answer)
                    return
                }
                gson.toJson(rows.map { it.first }) to checked.first().first
            }
            else -> {
                if (rows.size < 2) {
                    toast(R.string.msg_need_two_options)
                    return
                }
                if (rows.any { it.first.isEmpty() }) {
                    toast(R.string.msg_option_empty)
                    return
                }
                val checkedIndices = rows.indices.filter { rows[it].second }
                val ok = if (type == "single") checkedIndices.size == 1 else checkedIndices.isNotEmpty()
                if (!ok) {
                    toast(if (type == "single") R.string.msg_single_one_answer else R.string.msg_multi_one_answer)
                    return
                }
                // 答案按选项位置生成字母（与导入格式一致：A、A,B）
                val letters = checkedIndices.map { ('A' + it).toString() }
                gson.toJson(rows.map { it.first }) to letters.joinToString(",")
            }
        }

        val updated = q.copy(
            chapterId = chapterId,
            type = type,
            question = questionText,
            options = optionsJson,
            answer = answer,
            analysis = analysis,
            isValid = binding.swValid.isChecked
        )

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.updateQuestion(updated) }
            Toast.makeText(this@EditQuestionActivity, R.string.msg_update_success, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
