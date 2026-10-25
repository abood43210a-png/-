package com.thakirati.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object NoteStore {
    private const val PREFS = "thakirati_store"
    private const val KEY = "notes_json"
    fun all(context: Context): MutableList<Note> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Note(
                id=o.optString("id"), title=o.optString("title"), body=o.optString("body"),
                audioPath=o.optString("audioPath"), reminderAt=o.optLong("reminderAt"),
                completed=o.optBoolean("completed"), createdAt=o.optLong("createdAt")
            ))
        }
        return out
    }
    fun save(context: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body)
                .put("audioPath", n.audioPath).put("reminderAt", n.reminderAt)
                .put("completed", n.completed).put("createdAt", n.createdAt))
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
    }
    fun audioDir(context: Context) = File(context.filesDir, "audio").apply { mkdirs() }
}
