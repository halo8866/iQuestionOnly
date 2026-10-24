package com.iquestion.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.ExamAnswer
import com.iquestion.app.data.Question
import com.iquestion.app.databinding.ActivityExamHistoryDetailBinding
import com.iquestion.app.util.JsonImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 考试历史详情：回顾某次考试的试卷快照——每道题的题干、选项、当时的作答与正误。
 */
class ExamHistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamHistoryDetailBinding
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val recordId = intent.getLongExtra(ExamHistoryActivity.EXTRA_RECORD_ID, -1)
        if (recordId <= 0) {
            finish()
            return
        }

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@ExamHistoryDetailActivity).questionDao()
            val record = withContext(Dispatchers.IO) { dao.getExamRecordById(recordId) }
            val answers = withContext(Dispatchers.IO) { dao.getExamAnswers(recordId) }
            if (record == null) {
                Toast.makeText(this@ExamHistoryDetailActivity, R.string.msg_history_deleted, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            binding.tvDetailTitle.text = record.title
            binding.tvDetailTime.text = timeFormat.format(Date(record.createdAt))
            val rate = if (record.totalCount > 0) record.correctCount * 100.0 / record.totalCount else 0.0
            binding.tvDetailScore.text = getString(
                R.string.exam_score_format,
                record.correctCount, record.totalCount, rate
            )
            binding.tvDetailScore.setTextColor(getColor(if (rate >= 60) R.color.correct else R.color.wrong))

            binding.llAnswerContainer.removeAllViews()
            answers.forEach { ans ->
                binding.llAnswerContainer.addView(buildAnswerCard(ans))
            }
        }
    }

    /**
     * 构建单题回顾卡片
     */
    private fun buildAnswerCard(ans: ExamAnswer): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(getColor(R.color.card_background))
            isClickable = false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 顶部行：题型 + 正误
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = getTypeText(ans.type)
            textSize = 13f
            setTextColor(getColor(R.color.primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerRow.addView(TextView(this).apply {
            text = if (ans.isCorrect) "  ✓ 正确" else "  ✗ 错误"
            textSize = 13f
            setTextColor(getColor(if (ans.isCorrect) R.color.correct else R.color.wrong))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
            layoutParams = lp
        })
        col.addView(headerRow)

        // 题干
        col.addView(TextView(this).apply {
            text = "${ans.orderIndex + 1}. ${ans.question}"
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            layoutParams = lp
        })

        val snapshot = Question(
            bankId = 0,
            type = ans.type,
            question = ans.question,
            options = ans.options,
            answer = ans.answer,
            analysis = ans.analysis
        )

        if (ans.type == "essay") {
            col.addView(labelLine("你的解答：", ans.userAnswer,
                if (ans.isCorrect) R.color.correct else R.color.wrong, dp(12)))
            col.addView(labelLine("参考答案：", ans.answer, R.color.correct, dp(8)))
        } else {
            // 选项行：正确选项绿底，错选红底
            val options = JsonImporter.parseOptions(ans.options)
            val correctOpts = JsonImporter.getCorrectOptions(snapshot).toSet()
            val userOpts = if (ans.userAnswer == "未作答") {
                emptySet()
            } else {
                ans.userAnswer.split("；").toSet()
            }
            val optionBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            for (opt in options) {
                optionBox.addView(TextView(this).apply {
                    text = opt
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(
                        when {
                            correctOpts.contains(opt) -> Color.parseColor("#C8E6C9")
                            userOpts.contains(opt) -> Color.parseColor("#FFCDD2")
                            else -> Color.TRANSPARENT
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                })
            }
            col.addView(optionBox)

            col.addView(labelLine("你的作答：", ans.userAnswer,
                if (ans.isCorrect) R.color.correct else R.color.wrong, dp(10)))
            col.addView(labelLine("正确答案：", JsonImporter.getAnswerDisplay(snapshot),
                R.color.correct, dp(8)))
        }

        if (!ans.analysis.isNullOrBlank()) {
            col.addView(labelLine("解析：", ans.analysis, R.color.text_secondary, dp(8)))
        }

        card.addView(col)
        return card
    }

    private fun labelLine(prefix: String, content: String, colorRes: Int, topMargin: Int): TextView {
        return TextView(this).apply {
            text = "$prefix$content"
            textSize = 14f
            setTextColor(getColor(colorRes))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = topMargin }
        }
    }

    private fun getTypeText(type: String): String {
        return when (type) {
            "single" -> getString(R.string.type_single)
            "multiple" -> getString(R.string.type_multiple)
            "judge" -> getString(R.string.type_judge)
            "essay" -> getString(R.string.type_essay)
            else -> type
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
