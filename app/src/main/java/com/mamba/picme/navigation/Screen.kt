package com.mamba.picme.navigation

sealed class Screen(val route: String) {
    data object Chat : Screen("chat")
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery")
    data object TagControl : Screen("tag_control")
    data object Settings : Screen("settings")
    data object SettingsCategory : Screen("settings/{category}") {
        fun createRoute(category: String): String = "settings/$category"
    }
    data object Debug : Screen("debug")
    data object SearchTest : Screen("search_test")
    data object SentencePieceTest : Screen("sentencepiece_test")
    data object ModelCenter : Screen("model_center/{categoryTag}") {
        fun createRoute(categoryTag: String): String {
            return if (categoryTag.isNotBlank()) {
                "model_center/$categoryTag"
            } else {
                "model_center/"
            }
        }
    }

    data object PhotoEditor : Screen("photo_editor/{sourceUri}?recipeUri={recipeUri}") {
        fun createRoute(sourceUri: String, recipeUri: String? = null): String {
            val encodedSource = java.net.URLEncoder.encode(sourceUri, "UTF-8")
            return if (recipeUri != null) {
                val encodedRecipe = java.net.URLEncoder.encode(recipeUri, "UTF-8")
                "photo_editor/$encodedSource?recipeUri=$encodedRecipe"
            } else {
                "photo_editor/$encodedSource"
            }
        }
    }
}
