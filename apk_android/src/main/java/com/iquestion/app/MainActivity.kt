package com.iquestion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.iquestion.app.data.AppDatabase
import com.iquestion.app.data.QuestionDao
import com.iquestion.app.data.Subject
import com.iquestion.app.data.SubjectWithCounts
import com.iquestion.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dao: QuestionDao
    private val subjects = mutableListOf<SubjectWithCounts>()
    private lateinit var adapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dao = AppDatabase.getInstance(this).questionDao()

        adapter = SubjectAdapter(subjects,
            onClick = { subject -> openSubject(subject) },
            onDelete = { subject -> confirmDeleteSubject(subject) }
        )
        binding.rvBanks.layoutManager = LinearLayoutManager(this)
        binding.rvBanks.adapter = adapter

        binding.fabImport.setOnClickListener { showAddSubjectDialog() }

        // 底部导航：当前为"首页"
        binding.bottomNav.selectedItemId = R.id.nav_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_my) {
                val intent = Intent(this, MyActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
                finish()
                true
            } else {
                true // 已在"首页"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSubjects()
    }

    private fun loadSubjects() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { dao.getAllSubjectsWithCounts() }
            subjects.clear()
            subjects.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (subjects.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openSubject(subject: SubjectWithCounts) {
        val intent = Intent(this, SubjectActivity::class.java).apply {
            putExtra(SubjectActivity.EXTRA_SUBJECT_ID, subject.id)
            putExtra(SubjectActivity.EXTRA_SUBJECT_NAME, subject.name)
        }
        startActivity(intent)
    }

    /**
     * 手动添加科目
     */
    private fun showAddSubjectDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.hint_input_subject)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_add_subject)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.msg_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addSubject(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSubject(name: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = dao.findSubjectByName(name)
                    if (existing != null) {
                        throw IllegalArgumentException(getString(R.string.msg_subject_exists))
                    }
                    dao.insertSubject(Subject(name = name))
                }
                loadSubjects()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.msg_add_failed, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmDeleteSubject(subject: SubjectWithCounts) {
        AlertDialog.Builder(this)
            .setTitle(subject.name)
            .setMessage(R.string.msg_delete_subject_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteSubject(subject.id) }
                    loadSubjects()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class SubjectAdapter(
        private val items: List<SubjectWithCounts>,
        private val onClick: (SubjectWithCounts) -> Unit,
        private val onDelete: (SubjectWithCounts) -> Unit
    ) : RecyclerView.Adapter<SubjectAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSubjectName)
            val tvInfo: TextView = view.findViewById(R.id.tvSubjectInfo)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteSubject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subject, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val subject = items[position]
            holder.tvName.text = subject.name
            holder.tvInfo.text =
                "${subject.chapterCount} 章节 · ${subject.questionCount} 题 · 错题 ${subject.wrongCount}"
            holder.itemView.setOnClickListener { onClick(subject) }
            holder.btnDelete.setOnClickListener { onDelete(subject) }
        }

        override fun getItemCount() = items.size
    }
}
