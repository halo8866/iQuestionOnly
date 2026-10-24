package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.databinding.ActivityMyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的页面：集中入口错题本、题目管理、考试管理、导入导出
 */
class MyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyBinding
    private lateinit var dao: QuestionDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dao = AppDatabase.getInstance(this).questionDao()

        // 底部导航：当前为"我的"
        binding.bottomNav.selectedItemId = R.id.nav_my
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_home) {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
                finish()
                true
            } else {
                true // 已在"我的"
            }
        }

        binding.cardWrongBook.setOnClickListener { pickSubjectForWrongBook() }
        binding.cardQuestionManage.setOnClickListener { pickSubjectForManage() }
        binding.cardExamHistory.setOnClickListener {
            startActivity(Intent(this, ExamHistoryActivity::class.java))
        }
        binding.cardImportExport.setOnClickListener {
            startActivity(Intent(this, ImportExportActivity::class.java))
        }
    }

    /**
     * 选择科目后进入错题本
     */
    private fun pickSubjectForWrongBook() {
        lifecycleScope.launch {
            val subjects = withContext(Dispatchers.IO) { dao.getAllSubjectsWithCounts() }
            if (subjects.isEmpty()) {
                Toast.makeText(this@MyActivity, R.string.hint_no_subject, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val wrongSubjects = subjects.filter { it.wrongCount > 0 }
            val showList = if (wrongSubjects.isNotEmpty()) wrongSubjects else subjects
            val names = showList.map { "${it.name}（错题 ${it.wrongCount}）" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MyActivity)
                .setTitle(R.string.title_wrong_book)
                .setItems(names) { _, which ->
                    val s = showList[which]
                    val intent = Intent(this@MyActivity, WrongBookActivity::class.java).apply {
                        putExtra(WrongBookActivity.EXTRA_SUBJECT_ID, s.id)
                        putExtra(WrongBookActivity.EXTRA_SUBJECT_NAME, s.name)
                    }
                    startActivity(intent)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * 选择科目后进入题目管理
     */
    private fun pickSubjectForManage() {
        lifecycleScope.launch {
            val subjects = withContext(Dispatchers.IO) { dao.getAllSubjectsWithCounts() }
            if (subjects.isEmpty()) {
                Toast.makeText(this@MyActivity, R.string.hint_no_subject, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = subjects.map { "${it.name}（${it.questionCount} 题）" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MyActivity)
                .setTitle(R.string.title_question_manage)
                .setItems(names) { _, which ->
                    val s = subjects[which]
                    val intent = Intent(this@MyActivity, QuestionManageActivity::class.java).apply {
                        putExtra(QuestionManageActivity.EXTRA_SUBJECT_ID, s.id)
                        putExtra(QuestionManageActivity.EXTRA_SUBJECT_NAME, s.name)
                    }
                    startActivity(intent)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
