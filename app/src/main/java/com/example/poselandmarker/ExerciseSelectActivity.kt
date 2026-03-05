package com.example.poselandmarker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ExerciseSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exercise_select)

        // Hide the action bar for clean look
        supportActionBar?.hide()

        val exercises = loadExerciseList()
        val recycler = findViewById<RecyclerView>(R.id.recyclerExercises)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ExerciseAdapter(exercises) { exercise ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("exercise_file", exercise.fileName)
            intent.putExtra("exercise_name", exercise.displayName)
            startActivity(intent)
        }
    }

    private fun loadExerciseList(): List<ExerciseInfo> {
        val exercises = mutableListOf<ExerciseInfo>()

        // Load from assets/exercises/ folder
        try {
            val files = assets.list("exercises") ?: emptyArray()
            for (file in files.sorted()) {
                if (file.endsWith(".json")) {
                    val name = file.removeSuffix(".json")
                        .replace("-", " ")
                        .split(" ")
                        .joinToString(" ") { word ->
                            word.replaceFirstChar { it.uppercase() }
                        }

                    // Try to peek at the graph to get state count
                    val stateCount = try {
                        val json = assets.open("exercises/$file").bufferedReader().readText()
                        val statesStart = json.indexOf("\"states\"")
                        if (statesStart >= 0) {
                            // Count state objects (rough estimate)
                            val stateSection = json.substring(statesStart)
                            val matches = Regex("\"state_id\"").findAll(stateSection)
                            matches.count()
                        } else 0
                    } catch (_: Exception) { 0 }

                    exercises.add(ExerciseInfo(
                        fileName = "exercises/$file",
                        displayName = name,
                        stateCount = stateCount
                    ))
                }
            }
        } catch (_: Exception) {}

        // Also check for legacy tree.json in root assets
        if (exercises.isEmpty()) {
            exercises.add(ExerciseInfo("tree.json", "Tree Pose", 2))
        }

        return exercises
    }

    data class ExerciseInfo(
        val fileName: String,
        val displayName: String,
        val stateCount: Int = 0
    )

    // ── RecyclerView Adapter ─────────────────────────────────────────────
    inner class ExerciseAdapter(
        private val exercises: List<ExerciseInfo>,
        private val onClick: (ExerciseInfo) -> Unit
    ) : RecyclerView.Adapter<ExerciseAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtExerciseName)
            val txtInfo: TextView = view.findViewById(R.id.txtExerciseInfo)
            val iconCircle: View = view.findViewById(R.id.iconCircle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_exercise, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val exercise = exercises[position]
            holder.txtName.text = exercise.displayName
            holder.txtInfo.text = if (exercise.stateCount > 0) {
                "${exercise.stateCount} poses"
            } else {
                "Tap to start"
            }
            holder.itemView.setOnClickListener { onClick(exercise) }
        }

        override fun getItemCount() = exercises.size
    }
}
