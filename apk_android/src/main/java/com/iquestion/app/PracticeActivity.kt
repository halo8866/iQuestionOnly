package com.iquestion.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.ExamAnswer
import com.iquestion.app.data.ExamRecord
import com.iquestion.app.data.Question
import com.iquestion.app.databinding.ActivityPracticeBinding
import com.iquestion.app.util.JsonImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PracticeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_CHAPTER_NAME = "chapter_name"
        const val EXTRA_MODE = "mode"
        /** 错题练习：传入科目 id，题目来自该科目的错题本 */
        const val EXTRA_WRONG_SUBJECT_ID = "wrong_subject_id"
        /** 错题练习限定的题目 id 集合（ArrayList<Long>；由错题本章节/搜索筛选结果传入），为空则不限制 */
        const val EXTRA_WRONG_QUESTION_IDS = "wrong_question_ids"
        /** 科目考试：传入科目 id，跨章节抽题 */
        const val EXTRA_SUBJECT_EXAM_ID = "subject_exam_id"
        /** 科目考试限定章节（LongArray；未传则整科目范围抽题） */
        const val EXTRA_EXAM_CHAPTER_IDS = "extra_exam_chapter_ids"
        /** 科目考试各题型数量 */
        const val EXTRA_EXAM_COUNT_SINGLE = "exam_count_single"
        const val EXTRA_EXAM_COUNT_MULTIPLE = "exam_count_multiple"
        const val EXTRA_EXAM_COUNT_JUDGE = "exam_count_judge"
        const val EXTRA_EXAM_COUNT_ESSAY = "exam_count_essay"
        /** 科目考试时长（分钟数），0 表示不限时 */
        const val EXTRA_EXAM_DURATION_MINUTES = "exam_duration_minutes"
        /** 从暂存考试恢复：true 时 PracticeActivity 从 ExamDraftStore 加载草稿 */
        const val EXTRA_RESUME_DRAFT = "resume_draft"

        /** 顺序模式：按章节中题目的原始顺序逐题作答，做完一题立即显示答案 */
        const val MODE_SEQUENCE = 0
        /** 考试模式：题目随机抽取但按题型大类排序；全部做完交卷后才可查看答案与解析 */
        const val MODE_EXAM = 1
        /** 乱序模式：题目随机打乱，其余与顺序模式相同（逐题作答、即时显示答案） */
        const val MODE_RANDOM = 2

        /** 考试模式下题型大类的固定顺序 */
        private val EXAM_TYPE_ORDER = listOf("single", "multiple", "judge", "essay")
    }

    private lateinit var binding: ActivityPracticeBinding
    private var questions: List<Question> = emptyList()
    private var currentIndex = 0
    private var correctCount = 0
    private var mode = MODE_SEQUENCE
    private var examSubmitted = false
    private var wrongSubjectId = -1L // >0 表示错题练习
    private var wrongQuestionIds: Set<Long> = emptySet() // 错题练习限定题目；空则不限制
    private var examRecordSaved = false // 本次考试历史是否已保存
    private val userSelections = hashMapOf<Int, List<String>>() // index -> 选择项/解答文本
    private val resultMap = hashMapOf<Int, Boolean>() // index -> 是否正确
    private var isBuildingOptions = false // 构建选项期间抑制 RadioGroup 监听器误触发

    // 考试倒计时相关
    private var examDurationMinutes: Int = 0
    private var countDownTimer: CountDownTimer? = null
    private var tvCountdown: TextView? = null
    private var remainingMillis: Long = 0L // 当前剩余时长（毫秒），用于暂停/恢复
    private var isPaused: Boolean = false // 倒计时是否已暂停

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { requestExit() }

        mode = intent.getIntExtra(EXTRA_MODE, MODE_SEQUENCE)
        wrongSubjectId = intent.getLongExtra(EXTRA_WRONG_SUBJECT_ID, -1)
        @Suppress("UNCHECKED_CAST")
        wrongQuestionIds = (intent.getSerializableExtra(EXTRA_WRONG_QUESTION_IDS) as? ArrayList<Long>)
            ?.toSet() ?: emptySet()
        examDurationMinutes = intent.getIntExtra(EXTRA_EXAM_DURATION_MINUTES, 0)

        // 考试模式且设置了时长：在 toolbar 右上角显示倒计时
        if (mode == MODE_EXAM && examDurationMinutes > 0) {
            setupCountdownView()
        }

        val chapterName = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: getString(R.string.title_practice)
        binding.toolbar.title = if (mode == MODE_EXAM) {
            "$chapterName${getString(R.string.exam_title_suffix)}"
        } else {
            chapterName
        }

        binding.btnSubmit.setOnClickListener { submitAnswer() }
        binding.btnPrev.setOnClickListener { prevQuestion() }
        binding.btnNext.setOnClickListener { nextQuestion() }
        binding.btnRetry.setOnClickListener { resetAndRestart() }
        binding.btnReviewAnalysis.setOnClickListener { enterReview() }
        binding.btnBackHome.setOnClickListener { requestExit() }
        binding.btnSelfCorrect.setOnClickListener { selfRate(true) }
        binding.btnSelfWrong.setOnClickListener { selfRate(false) }

        loadQuestions()
    }

    private fun loadQuestions() {
        lifecycleScope.launch {
            val subjectExamId = intent.getLongExtra(EXTRA_SUBJECT_EXAM_ID, -1)
            val resumeDraft = intent.getBooleanExtra(EXTRA_RESUME_DRAFT, false)
            // 恢复暂存考试时读取草稿（读取后立即清除，表示已消耗；中途再次暂存会生成新草稿）
            val draft = if (resumeDraft) {
                val d = com.iquestion.app.util.ExamDraftStore.load(this@PracticeActivity)
                com.iquestion.app.util.ExamDraftStore.clear(this@PracticeActivity)
                d
            } else null
            val rawQuestions: List<Question> = withContext(Dispatchers.IO) {
                when {
                    draft != null -> {
                        // 恢复暂存考试：按保存的题目 ID 加载（已删除的题目自动忽略）
                        val dao = AppDatabase.getInstance(this@PracticeActivity).questionDao()
                        val loaded = dao.getQuestionsByIds(draft.questionIds)
                        // 按保存顺序排列
                        val idOrder = draft.questionIds.withIndex().associate { it.value to it.index }
                        loaded.sortedBy { idOrder[it.id] ?: Int.MAX_VALUE }
                    }
                    wrongSubjectId > 0 -> {
                        // 错题练习：题目来自该科目的错题本，仅有效错题（无效题不参与练习）；
                        // 若指定了 wrongQuestionIds（错题本章节/搜索筛选结果），仅练习其中题目
                        AppDatabase.getInstance(this@PracticeActivity).questionDao()
                            .getWrongQuestionsBySubject(wrongSubjectId)
                            .filter { it.isValid }
                            .filter { wrongQuestionIds.isEmpty() || wrongQuestionIds.contains(it.id) }
                            .map { item ->
                                Question(
                                    id = item.id,
                                    bankId = item.bankId,
                                    chapterId = item.chapterId,
                                    type = item.type,
                                    question = item.question,
                                    options = item.options,
                                    answer = item.answer,
                                    analysis = item.analysis,
                                    isValid = item.isValid
                                )
                            }
                    }
                    subjectExamId > 0 -> {
                        // 科目考试：按所选章节抽题（未选章节则整科目范围），按各题型设定数量随机抽取
                        val dao = AppDatabase.getInstance(this@PracticeActivity).questionDao()
                        val chapterIds = intent.getLongArrayExtra(EXTRA_EXAM_CHAPTER_IDS)
                        val all = if (chapterIds != null && chapterIds.isNotEmpty()) {
                            dao.getValidQuestionsByChapters(chapterIds.toList())
                        } else {
                            dao.getValidQuestionsBySubject(subjectExamId)
                        }
                        val counts = mapOf(
                            "single" to intent.getIntExtra(EXTRA_EXAM_COUNT_SINGLE, 5),
                            "multiple" to intent.getIntExtra(EXTRA_EXAM_COUNT_MULTIPLE, 3),
                            "judge" to intent.getIntExtra(EXTRA_EXAM_COUNT_JUDGE, 3),
                            "essay" to intent.getIntExtra(EXTRA_EXAM_COUNT_ESSAY, 2)
                        )
                        val grouped = all.groupBy { it.type }
                        val result = mutableListOf<Question>()
                        for (type in EXAM_TYPE_ORDER) {
                            val pool = grouped[type] ?: emptyList()
                            val n = counts[type] ?: 0
                            result.addAll(pool.shuffled().take(n))
                        }
                        result
                    }
                    else -> {
                        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1)
                        if (chapterId <= 0) {
                            emptyList()
                        } else {
                            AppDatabase.getInstance(this@PracticeActivity).questionDao()
                                .getQuestionsByChapter(chapterId)
                                .filter { it.isValid }
                        }
                    }
                }
            }
            if (rawQuestions.isEmpty()) {
                Toast.makeText(this@PracticeActivity, R.string.hint_no_questions, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            questions = arrangeQuestions(rawQuestions, mode)
            // 恢复暂存考试：还原位置、作答、剩余时长
            if (draft != null) {
                currentIndex = draft.currentIndex.coerceIn(0, (questions.size - 1).coerceAtLeast(0))
                userSelections.clear()
                userSelections.putAll(draft.userSelections)
                remainingMillis = draft.remainingMillis
                if (examDurationMinutes <= 0) examDurationMinutes = draft.durationMinutes
            } else {
                currentIndex = 0
                correctCount = 0
                userSelections.clear()
            }
            correctCount = 0
            examSubmitted = false
            examRecordSaved = false
            resultMap.clear()
            showQuestion()
            // 考试模式且设置了时长：开始倒计时
            if (mode == MODE_EXAM && examDurationMinutes > 0) {
                tvCountdown?.visibility = View.VISIBLE
                startCountdown()
            }
        }
    }

    /** 记录做题进度：章节「已做题数」用于章节页展示（同一题重复作答仅累计次数） */
    private fun markQuestionDone(questionId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getInstance(this@PracticeActivity).questionDao().markQuestionDone(questionId)
        }
    }

    /**
     * 在 Toolbar 右上角添加倒计时 TextView（仅考试模式且设置了时长时创建）
     */
    private fun setupCountdownView() {
        tvCountdown = TextView(this).apply {
            setTextColor(getColor(R.color.white))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            // 点击暂停/恢复倒计时
            setOnClickListener { togglePause() }
            // 长按弹出「暂存考试」确认框
            setOnLongClickListener {
                showSaveDraftDialog()
                true
            }
        }
        val params = androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            androidx.appcompat.widget.Toolbar.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        binding.toolbar.addView(tvCountdown, params)
    }

    /** 启动考试倒计时（题目加载完成后调用）。remainingMillis>0 时从该剩余时间开始（恢复场景） */
    private fun startCountdown() {
        if (examDurationMinutes <= 0) return
        val startMs = if (remainingMillis > 0) remainingMillis else examDurationMinutes * 60 * 1000L
        countDownTimer?.cancel()
        isPaused = false
        countDownTimer = object : CountDownTimer(startMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                val totalSeconds = (millisUntilFinished / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                tvCountdown?.text = getString(R.string.countdown_format, minutes, seconds)
                // 剩余不足 1 分钟时变红提示
                tvCountdown?.setTextColor(
                    getColor(if (totalSeconds <= 60) R.color.wrong else R.color.white)
                )
            }

            override fun onFinish() {
                remainingMillis = 0
                tvCountdown?.text = getString(R.string.countdown_format, 0, 0)
                onTimeUp()
            }
        }.start()
    }

    /** 点击倒计时：暂停 / 恢复 */
    private fun togglePause() {
        if (isPaused) {
            // 恢复：从 remainingMillis 继续，重新允许答题
            startCountdown()
            setAnsweringEnabled(true)
        } else {
            // 暂停：取消计时器，保留 remainingMillis，禁止答题
            countDownTimer?.cancel()
            countDownTimer = null
            isPaused = true
            setAnsweringEnabled(false)
            val cur = tvCountdown?.text?.toString().orEmpty()
            tvCountdown?.text = "$cur  ${getString(R.string.label_paused)}"
        }
    }

    /** 停止倒计时（交卷、退出、重开时调用） */
    private fun stopCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    /** 长按倒计时：弹出「暂存考试」确认框，确认后保存草稿并退出 */
    private fun showSaveDraftDialog() {
        if (examSubmitted) return
        saveCurrentAnswer()
        AlertDialog.Builder(this)
            .setTitle(R.string.msg_save_draft_title)
            .setMessage(R.string.msg_save_draft_message)
            .setPositiveButton(R.string.dialog_save_draft_confirm) { _, _ ->
                saveDraftAndFinish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 保存当前考试状态为草稿并退出 */
    private fun saveDraftAndFinish() {
        val subjectId = intent.getLongExtra(EXTRA_SUBJECT_EXAM_ID, -1)
        if (subjectId <= 0 || questions.isEmpty()) {
            Toast.makeText(this, R.string.msg_save_draft_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val subjectName = intent.getStringExtra(EXTRA_CHAPTER_NAME).orEmpty()
        val chapterIds = intent.getLongArrayExtra(EXTRA_EXAM_CHAPTER_IDS)?.toList() ?: emptyList()
        val draft = com.iquestion.app.util.ExamDraft(
            subjectId = subjectId,
            subjectName = subjectName,
            chapterIds = chapterIds,
            countSingle = intent.getIntExtra(EXTRA_EXAM_COUNT_SINGLE, 0),
            countMultiple = intent.getIntExtra(EXTRA_EXAM_COUNT_MULTIPLE, 0),
            countJudge = intent.getIntExtra(EXTRA_EXAM_COUNT_JUDGE, 0),
            countEssay = intent.getIntExtra(EXTRA_EXAM_COUNT_ESSAY, 0),
            durationMinutes = examDurationMinutes,
            remainingMillis = remainingMillis,
            questionIds = questions.map { it.id },
            currentIndex = currentIndex,
            userSelections = userSelections.toMap(),
            savedAt = System.currentTimeMillis()
        )
        com.iquestion.app.util.ExamDraftStore.save(this, draft)
        stopCountdown()
        Toast.makeText(this, R.string.msg_draft_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 倒计时结束：弹出自动交卷确认，确认按钮 3 秒倒计时结束后自动交卷 */
    private fun onTimeUp() {
        stopCountdown()
        if (examSubmitted) return
        saveCurrentAnswer()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.msg_time_up_title)
            .setMessage(R.string.msg_time_up_message)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_hand_in_confirm) { _, _ -> handInExam() }
            .create()
        dialog.show()
        val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val originalText = positiveBtn.text
        var remain = 3
        positiveBtn.text = "$originalText（$remain）"
        // 倒计时期间按钮保持可点击，用户可提前确认交卷；倒计时归零自动交卷
        val timer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remain--
                positiveBtn.text = "$originalText（$remain）"
            }
            override fun onFinish() {
                if (!examSubmitted) handInExam()
                dialog.dismiss()
            }
        }.start()
        positiveBtn.setOnClickListener {
            timer.cancel()
            handInExam()
            dialog.dismiss()
        }
    }

    /**
     * 判错 → 记入/刷新错题本（保存用户当时的错误作答）；判对 → 从错题本消除
     */
    private fun syncWrongRecord(q: Question, userAnswer: List<String>, correct: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(this@PracticeActivity).questionDao()
            if (correct) {
                dao.deleteWrongByQuestion(q.id)
            } else {
                dao.upsertWrongRecord(
                    questionId = q.id,
                    wrongAnswer = userAnswer.joinToString("；").ifEmpty { "未作答" },
                    wrongAt = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 按模式排列题目：
     * - 顺序模式：保持题库原始顺序
     * - 乱序模式：题目随机打乱
     * - 考试模式：按 单选→多选→判断→解答 的大类顺序排列，每个大类内部随机打乱
     */
    private fun arrangeQuestions(list: List<Question>, mode: Int): List<Question> {
        when (mode) {
            MODE_RANDOM -> return list.shuffled()
            MODE_EXAM -> {
                val grouped = list.groupBy { it.type }
                val result = mutableListOf<Question>()
                for (type in EXAM_TYPE_ORDER) {
                    grouped[type]?.shuffled()?.let { result.addAll(it) }
                }
                // 兜底：未知题型放到最后，同样随机
                val known = EXAM_TYPE_ORDER.toSet()
                list.filter { it.type !in known }.shuffled().let { result.addAll(it) }
                return result
            }
            else -> return list
        }
    }

    private fun showQuestion() {
        if (currentIndex >= questions.size) {
            showFinish()
            return
        }

        val q = questions[currentIndex]
        val isEssay = q.type == "essay"
        val hasResult = resultMap.containsKey(currentIndex)
        val savedSelection = userSelections[currentIndex] ?: emptyList()

        // 考试模式的两个阶段：答题期（交卷前）、查看期（交卷后）
        val examAnswering = mode == MODE_EXAM && !examSubmitted
        val examReview = mode == MODE_EXAM && examSubmitted

        // 输入区是否锁定（不可再修改）
        val locked = when {
            examAnswering -> false
            examReview -> true
            else -> hasResult || (isEssay && savedSelection.isNotEmpty()) // 顺序模式
        }

        binding.resultLayout.visibility = View.GONE
        binding.selfRateLayout.visibility = View.GONE
        binding.finishLayout.visibility = View.GONE
        binding.bottomNavLayout.visibility = View.VISIBLE

        // 提交按钮：练习模式且为多选/解答题才显示（单选/判断选中即自动提交，无需按钮）
        binding.btnSubmit.visibility = if (mode != MODE_EXAM && q.type != "single" && q.type != "judge") View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !locked

        if (mode == MODE_EXAM) {
            val answeredCount = userSelections.count { it.value.isNotEmpty() }
            binding.tvProgress.text =
                getString(R.string.exam_progress_format, currentIndex + 1, questions.size, answeredCount)
        } else {
            binding.tvProgress.text = "第 ${currentIndex + 1} / ${questions.size} 题"
        }
        binding.tvType.text = getTypeText(q.type)
        binding.tvQuestion.text = q.question

        // 标签文案随题型切换
        if (isEssay) {
            binding.tvYourAnswerLabel.setText(R.string.label_your_solution)
            binding.tvCorrectAnswerLabel.setText(R.string.label_reference_answer)
        } else {
            binding.tvYourAnswerLabel.setText(R.string.label_your_answer)
            binding.tvCorrectAnswerLabel.setText(R.string.label_correct_answer)
        }

        buildOptions(q, savedSelection, locked)

        // 结果/参考答案面板展示逻辑
        when {
            examReview && isEssay && !hasResult -> {
                // 交卷后：解答题未自评 → 显示参考答案和自评按钮
                showEssayAwaitingRate(q, savedSelection.firstOrNull().orEmpty())
            }
            examReview -> {
                // 交卷后：客观题判分结果，或解答题已自评的结果
                showResult(q, savedSelection, resultMap[currentIndex] ?: false)
            }
            mode != MODE_EXAM && hasResult -> {
                showResult(q, savedSelection, resultMap[currentIndex] ?: false)
            }
            mode != MODE_EXAM && isEssay && savedSelection.isNotEmpty() -> {
                // 顺序模式：解答题已提交待自评
                showEssayAwaitingRate(q, savedSelection.first())
            }
            // 考试答题期：不显示任何答案
        }

        // 考试暂停时，切换题目后仍保持答题控件禁用
        if (mode == MODE_EXAM && !examSubmitted && isPaused) {
            setAnsweringEnabled(false)
        }

        updateNavButtons()
    }

    /**
     * 构建作答区域：解答题用 EditText，多选题用 CheckBox，单选/判断用 RadioGroup
     */
    private fun buildOptions(q: Question, savedSelection: List<String>, locked: Boolean) {
        isBuildingOptions = true
        val container = binding.optionsContainer
        container.removeAllViews()
        val options = JsonImporter.parseOptions(q.options)

        when {
            q.type == "essay" -> {
                val et = EditText(this).apply {
                    hint = getString(R.string.hint_essay_input)
                    setText(savedSelection.firstOrNull() ?: "")
                    minLines = 5
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    setTextSize(16f)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setBackgroundResource(R.drawable.bg_essay_edit)
                    isEnabled = !locked
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                container.addView(et, params)
            }
            q.type == "multiple" -> {
                for (opt in options) {
                    val cb = CheckBox(this).apply {
                        text = opt
                        textSize = 16f
                        setPadding(0, dp(14), 0, dp(14))
                        isChecked = savedSelection.contains(opt)
                        isEnabled = !locked
                        setOnCheckedChangeListener { button, _ ->
                            if (locked) {
                                button.isChecked = savedSelection.contains(opt)
                            }
                        }
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    container.addView(cb, params)
                }
            }
            else -> {
                // 单选 / 判断
                val radioGroup = RadioGroup(this).apply {
                    orientation = RadioGroup.VERTICAL
                }
                for (opt in options) {
                    val rb = RadioButton(this).apply {
                        text = opt
                        textSize = 16f
                        setPadding(0, dp(14), 0, dp(14))
                        isChecked = savedSelection.contains(opt)
                        isEnabled = !locked
                    }
                    radioGroup.addView(rb)
                }
                radioGroup.setOnCheckedChangeListener { group, _ ->
                    if (isBuildingOptions) return@setOnCheckedChangeListener
                    if (locked) {
                        restoreRadioSelection(group, savedSelection)
                        return@setOnCheckedChangeListener
                    }
                    // 单选/判断：选中即自动提交/保存跳转
                    val selected = getSelectedOptions()
                    if (selected.isEmpty()) return@setOnCheckedChangeListener
                    handleSingleJudgeAutoSubmit()
                }
                container.addView(radioGroup)
            }
        }
        isBuildingOptions = false
    }

    /**
     * 单选/判断题选中即自动处理：
     * - 练习模式：自动提交判分，展示对错与解析
     * - 考试模式：保存作答并自动跳下一题（最后一题除外，需手动交卷避免误触）
     */
    private fun handleSingleJudgeAutoSubmit() {
        val q = questions[currentIndex]
        if (q.type != "single" && q.type != "judge") return
        if (resultMap.containsKey(currentIndex)) return
        if (mode == MODE_EXAM) {
            saveCurrentAnswer()
            val isLast = currentIndex >= questions.size - 1
            if (!isLast) {
                currentIndex++
                showQuestion()
            }
        } else {
            submitAnswer()
        }
    }

    private fun restoreRadioSelection(group: RadioGroup, savedSelection: List<String>) {
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as RadioButton
            rb.isChecked = savedSelection.contains(rb.text.toString())
        }
    }

    /**
     * 获取用户作答内容（选项文本列表；解答题为单元素文本列表）
     */
    private fun getSelectedOptions(): List<String> {
        val container = binding.optionsContainer
        val selected = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is EditText -> {
                    val t = child.text.toString().trim()
                    if (t.isNotEmpty()) selected.add(t)
                }
                is CheckBox -> {
                    if (child.isChecked) selected.add(child.text.toString())
                }
                is RadioGroup -> {
                    for (j in 0 until child.childCount) {
                        val rb = child.getChildAt(j) as RadioButton
                        if (rb.isChecked) selected.add(rb.text.toString())
                    }
                }
            }
        }
        return selected
    }

    /**
     * 考试答题期：离开当前题前保存作答（允许为空，空表示未答）
     */
    private fun saveCurrentAnswer() {
        if (mode != MODE_EXAM || examSubmitted) return
        if (resultMap.containsKey(currentIndex)) return
        val selected = getSelectedOptions()
        userSelections[currentIndex] = selected
    }

    /**
     * 练习模式（顺序/乱序）的单题提交
     */
    private fun submitAnswer() {
        if (mode == MODE_EXAM) return
        if (resultMap.containsKey(currentIndex)) return
        val selected = getSelectedOptions()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.msg_please_answer, Toast.LENGTH_SHORT).show()
            return
        }

        val q = questions[currentIndex]
        userSelections[currentIndex] = selected
        // 记录做题进度：章节「已做题数」+1（考试模式不计数）
        markQuestionDone(q.id)

        if (q.type == "essay") {
            // 解答题为主观题：展示参考答案，等待用户自评
            binding.btnSubmit.isEnabled = false
            setEssayEditEnabled(false)
            showEssayAwaitingRate(q, selected.first())
        } else {
            val correct = JsonImporter.isAnswerCorrect(selected, q)
            resultMap[currentIndex] = correct
            if (correct) correctCount++
            // 记入/消除错题
            syncWrongRecord(q, selected, correct)
            binding.btnSubmit.isEnabled = false
            showResult(q, selected, correct)
        }
        updateNavButtons()
    }

    /**
     * 交卷：确认后自动判分所有客观题，进入查看模式
     */
    private fun confirmHandIn() {
        saveCurrentAnswer()
        val answered = userSelections.count { it.value.isNotEmpty() }
        val unanswered = questions.size - answered

        val message = buildString {
            append(getString(R.string.dialog_hand_in_message))
            if (unanswered > 0) {
                append("\n\n")
                append("你还有 $unanswered 道题未作答（已答 $answered 道），未作答将记为错误。")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_hand_in_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_hand_in_confirm) { _, _ -> handInExam() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handInExam() {
        stopCountdown()
        tvCountdown?.visibility = View.GONE
        // 交卷即视为考试结束，清除可能存在的草稿
        com.iquestion.app.util.ExamDraftStore.clear(this)
        // 考试结果计入章节进度：交卷时把已作答的题目标记为已做（未作答不计）
        questions.forEachIndexed { index, q ->
            val selected = userSelections[index] ?: emptyList()
            if (selected.isNotEmpty()) markQuestionDone(q.id)
        }
        // 客观题自动判分；解答题在查看模式中自评
        questions.forEachIndexed { index, q ->
            if (q.type != "essay") {
                val selected = userSelections[index] ?: emptyList()
                val correct = selected.isNotEmpty() && JsonImporter.isAnswerCorrect(selected, q)
                resultMap[index] = correct
                if (correct) correctCount++
                // 记入/消除错题（未作答也记为错题，wrongAnswer 为「未作答」）
                syncWrongRecord(q, selected, correct)
            }
        }
        examSubmitted = true
        currentIndex = 0
        // 交卷后立即出成绩页；之后可在成绩页点「查看题目解析」逐题回看、自评解答题
        showFinish()
    }

    /**
     * 解答题自评（顺序模式提交后、考试模式交卷后均可使用）
     */
    private fun selfRate(correct: Boolean) {
        if (resultMap.containsKey(currentIndex)) return
        resultMap[currentIndex] = correct
        if (correct) correctCount++
        binding.selfRateLayout.visibility = View.GONE
        val q = questions[currentIndex]
        val selected = userSelections[currentIndex] ?: emptyList()
        // 自评结果同样记入/消除错题
        syncWrongRecord(q, selected, correct)
        showResult(q, selected, correct)
        updateNavButtons()
    }

    /**
     * 解答题待自评：展示参考答案和自评按钮
     */
    private fun showEssayAwaitingRate(q: Question, userText: String) {
        binding.resultLayout.visibility = View.VISIBLE
        binding.tvResultTitle.text = getString(R.string.msg_self_rate_tip)
        binding.tvResultTitle.setTextColor(getColor(R.color.primary))
        binding.tvYourAnswer.text = userText.ifEmpty { "未作答" }
        binding.tvCorrectAnswer.text = q.answer
        setAnalysisText(q)
        binding.selfRateLayout.visibility = View.VISIBLE
    }

    /**
     * 展示判分结果（客观题自动判分 / 解答题自评结果）
     */
    private fun showResult(q: Question, selected: List<String>, correct: Boolean) {
        binding.resultLayout.visibility = View.VISIBLE
        binding.selfRateLayout.visibility = View.GONE
        val title = binding.tvResultTitle
        if (correct) {
            title.text = getString(R.string.msg_answer_correct)
            title.setTextColor(getColor(R.color.correct))
        } else {
            title.text = getString(R.string.msg_answer_wrong)
            title.setTextColor(getColor(R.color.wrong))
        }

        binding.tvYourAnswer.text = selected.joinToString("；").ifEmpty { "未作答" }
        binding.tvCorrectAnswer.text = JsonImporter.getAnswerDisplay(q)
        setAnalysisText(q)

        // 解答题无选项可高亮
        if (q.type != "essay") {
            highlightOptions(q, selected)
        }
    }

    private fun setAnalysisText(q: Question) {
        if (!q.analysis.isNullOrBlank()) {
            binding.tvAnalysis.text = q.analysis
            binding.tvAnalysis.visibility = View.VISIBLE
        } else {
            binding.tvAnalysis.visibility = View.GONE
        }
    }

    private fun setEssayEditEnabled(enabled: Boolean) {
        val container = binding.optionsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is EditText) {
                child.isEnabled = enabled
            }
        }
    }

    /** 暂停时禁用所有答题控件（单选/多选/解答），恢复时重新启用 */
    private fun setAnsweringEnabled(enabled: Boolean) {
        val container = binding.optionsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            when (child) {
                is EditText -> child.isEnabled = enabled
                is CheckBox -> child.isEnabled = enabled
                is RadioGroup -> {
                    for (j in 0 until child.childCount) {
                        (child.getChildAt(j) as? RadioButton)?.isEnabled = enabled
                    }
                }
            }
        }
    }

    private fun highlightOptions(q: Question, selected: List<String>) {
        val container = binding.optionsContainer
        val correctOptions = JsonImporter.getCorrectOptions(q).toSet()

        fun highlightChild(child: View) {
            val text = when (child) {
                is CheckBox -> child.text.toString()
                is RadioButton -> child.text.toString()
                else -> return
            }
            val isCorrect = correctOptions.contains(text)
            val isSelected = selected.contains(text)
            when {
                isCorrect -> child.setBackgroundColor(Color.parseColor("#C8E6C9"))
                isSelected -> child.setBackgroundColor(Color.parseColor("#FFCDD2"))
                else -> child.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is RadioGroup) {
                for (j in 0 until child.childCount) {
                    highlightChild(child.getChildAt(j))
                }
            } else {
                highlightChild(child)
            }
        }
    }

    private fun prevQuestion() {
        if (currentIndex > 0) {
            // 考试答题期：离开当前题前自动保存作答
            if (mode == MODE_EXAM && !examSubmitted) saveCurrentAnswer()
            currentIndex--
            showQuestion()
        }
    }

    private fun nextQuestion() {
        val isLast = currentIndex >= questions.size - 1

        when {
            // 考试答题期 + 最后一题 → 交卷
            mode == MODE_EXAM && !examSubmitted && isLast -> {
                confirmHandIn()
            }
            // 考试查看期 + 最后一题 → 查看成绩
            mode == MODE_EXAM && examSubmitted && isLast -> {
                val unrated = questions.size - resultMap.size
                if (unrated > 0) {
                    Toast.makeText(
                        this,
                        getString(R.string.msg_unrated_tip, unrated),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showFinish()
            }
            // 练习模式 + 最后一题 → 结束练习
            mode != MODE_EXAM && isLast -> {
                if (resultMap.size < questions.size) {
                    val unanswered = questions.size - resultMap.size
                    Toast.makeText(
                        this,
                        "还有 $unanswered 题未作答/未自评，将记为错误",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showFinish()
            }
            else -> {
                // 考试答题期翻页前保存作答
                if (mode == MODE_EXAM && !examSubmitted) saveCurrentAnswer()
                currentIndex++
                showQuestion()
            }
        }
    }

    private fun updateNavButtons() {
        binding.btnPrev.isEnabled = currentIndex > 0
        val isLast = currentIndex == questions.size - 1
        binding.btnNext.text = when {
            !isLast -> getString(R.string.btn_next)
            mode == MODE_EXAM && !examSubmitted -> getString(R.string.btn_hand_in)
            mode == MODE_EXAM && examSubmitted -> getString(R.string.btn_view_score)
            else -> getString(R.string.btn_finish)
        }
    }

    /**
     * 考试结束时保存考试历史：快照每道题的题干、选项、正确答案、用户作答与正误。
     * 快照独立于题库，之后题目被修改/删除不影响历史回顾。
     */
    private fun saveExamRecord(onSaved: (() -> Unit)? = null) {
        if (mode != MODE_EXAM || examRecordSaved) {
            onSaved?.invoke()
            return
        }
        examRecordSaved = true

        val title = intent.getStringExtra(EXTRA_CHAPTER_NAME)
            ?.removeSuffix(getString(R.string.exam_title_suffix))
            ?: getString(R.string.title_practice)

        val answers = questions.mapIndexed { index, q ->
            val selected = userSelections[index] ?: emptyList()
            val userAnswer = if (q.type == "essay") {
                selected.firstOrNull()?.trim().orEmpty().ifEmpty { "未作答" }
            } else {
                selected.joinToString("；").ifEmpty { "未作答" }
            }
            ExamAnswer(
                recordId = 0,
                orderIndex = index,
                type = q.type,
                question = q.question,
                options = q.options,
                answer = q.answer,
                analysis = q.analysis,
                userAnswer = userAnswer,
                isCorrect = resultMap[index] == true
            )
        }

        val record = ExamRecord(
            title = title,
            totalCount = questions.size,
            correctCount = correctCount,
            createdAt = System.currentTimeMillis()
        )

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                AppDatabase.getInstance(this@PracticeActivity).questionDao()
                    .insertExamWithAnswers(record, answers)
            }
            onSaved?.let { callback ->
                withContext(Dispatchers.Main) { callback() }
            }
        }
    }

    /**
     * 退出/返回逻辑：
     * - 考试答题中：弹出「是否结束考试」确认框，确认后自动交卷
     * - 交卷后正在「查看题目解析」：返回键回到成绩页
     * - 成绩页或其他情况：考试已交卷则确保历史落库后退出，其余直接退出
     * 未自评的解答题按错误记录，与成绩页「未自评记为错误」的口径一致。
     */
    private fun requestExit() {
        if (mode == MODE_EXAM && !examSubmitted) {
            showEndExamDialog()
            return
        }
        if (mode == MODE_EXAM && examSubmitted &&
            binding.finishLayout.visibility != View.VISIBLE
        ) {
            showFinish()
            return
        }
        if (mode == MODE_EXAM && examSubmitted && !examRecordSaved) {
            saveExamRecord { finish() }
        } else {
            finish()
        }
    }

    /**
     * 考试答题期按返回：确认是否结束考试（结束即自动交卷判分，未作答记为错误）
     */
    private fun showEndExamDialog() {
        saveCurrentAnswer()
        val answered = userSelections.count { it.value.isNotEmpty() }
        val unanswered = questions.size - answered
        val message = buildString {
            append(getString(R.string.dialog_end_exam_message))
            if (unanswered > 0) {
                append("\n\n")
                append("你还有 $unanswered 道题未作答（已答 $answered 道），未作答将记为错误。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_end_exam_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_end_exam_confirm) { _, _ -> handInExam() }
            .setNegativeButton(R.string.dialog_end_exam_cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        requestExit()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCountdown()
    }

    private fun showFinish() {
        // 注意：考试历史不在此保存——交卷后会先到成绩页，用户可能再进入解析页自评解答题，
        // 快照在退出（requestExit）或重开（resetAndRestart）时才落库，以包含最终自评结果。

        binding.resultLayout.visibility = View.GONE
        binding.selfRateLayout.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.bottomNavLayout.visibility = View.GONE
        binding.optionsContainer.removeAllViews()
        binding.tvQuestion.visibility = View.GONE
        binding.tvType.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE

        binding.finishLayout.visibility = View.VISIBLE
        val total = questions.size
        val rate = if (total > 0) correctCount * 100.0 / total else 0.0
        binding.tvScore.text = if (mode == MODE_EXAM) {
            getString(R.string.msg_exam_result, rate, correctCount, total)
        } else {
            getString(R.string.msg_practice_result, rate, correctCount, total)
        }

        // 「查看题目解析」仅考试模式提供（顺序模式做题时已即时显示答案）
        binding.btnReviewAnalysis.visibility = if (mode == MODE_EXAM) View.VISIBLE else View.GONE

        // 交卷后尚未自评的解答题：提示去解析页自评，未自评记为错误
        val unrated = total - resultMap.size
        if (mode == MODE_EXAM && unrated > 0) {
            binding.tvFinishTip.text = getString(R.string.msg_finish_unrated_tip, unrated)
            binding.tvFinishTip.visibility = View.VISIBLE
        } else {
            binding.tvFinishTip.visibility = View.GONE
        }
    }

    /**
     * 成绩页 → 查看题目解析：进入交卷后的查看模式，
     * 可逐题翻看答案与解析，解答题可对照参考答案自评（自评后成绩页分数自动更新）。
     */
    private fun enterReview() {
        currentIndex = 0
        binding.finishLayout.visibility = View.GONE
        binding.tvQuestion.visibility = View.VISIBLE
        binding.tvType.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        showQuestion()
    }

    private fun resetAndRestart() {
        // 已交卷的考试直接重开时，先把本次试卷快照落库（含解析页中的解答题自评结果）；
        // saveExamRecord 同步捕获作答数据后再异步写入，需在清空状态之前调用
        if (mode == MODE_EXAM && examSubmitted && !examRecordSaved) {
            saveExamRecord()
        }
        currentIndex = 0
        correctCount = 0
        examSubmitted = false
        examRecordSaved = false
        userSelections.clear()
        resultMap.clear()
        val subjectExamId = intent.getLongExtra(EXTRA_SUBJECT_EXAM_ID, -1)
        if (subjectExamId > 0) {
            // 科目考试：重新从题库随机抽题
            binding.finishLayout.visibility = View.GONE
            binding.tvQuestion.visibility = View.VISIBLE
            binding.tvType.visibility = View.VISIBLE
            binding.tvProgress.visibility = View.VISIBLE
            loadQuestions()
        } else {
            // 章节考试/乱序模式：重新打乱已有题目
            if (mode == MODE_EXAM || mode == MODE_RANDOM) {
                questions = arrangeQuestions(questions, mode)
            }
            binding.finishLayout.visibility = View.GONE
            binding.tvQuestion.visibility = View.VISIBLE
            binding.tvType.visibility = View.VISIBLE
            binding.tvProgress.visibility = View.VISIBLE
            showQuestion()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun getTypeText(type: String): String {
        return when (type) {
            "single" -> getString(R.string.type_single)
            "multiple" -> getString(R.string.type_multiple)
            "judge" -> getString(R.string.type_judge)
            "essay" -> getString(R.string.type_essay)
            else -> type
        }
    }
}
