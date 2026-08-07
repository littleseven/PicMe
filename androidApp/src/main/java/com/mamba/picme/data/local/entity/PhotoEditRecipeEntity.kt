package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_edit_recipes")
data class PhotoEditRecipeEntity(
    @PrimaryKey
    val outputUri: String,
    val sourceUri: String,
    val recipeJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)
