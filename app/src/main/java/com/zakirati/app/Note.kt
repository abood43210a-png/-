package com.thakirati.app

data class Note(
    var id: String,
    var title: String,
    var body: String,
    var audioPath: String = "",
    var reminderAt: Long = 0L,
    var completed: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)
