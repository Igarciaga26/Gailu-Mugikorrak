package com.example.basiclayout01

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items_table")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val titleResId: Int,
    val drawableResId: Int,
    val category: String // "body" or "favorite"
)
