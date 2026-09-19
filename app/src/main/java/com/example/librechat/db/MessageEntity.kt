package com.example.librechat.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val fromId: String,
    val fromName: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long
)
