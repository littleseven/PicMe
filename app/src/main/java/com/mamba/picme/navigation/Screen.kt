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

    data object PhotoEditor : Screen("photo_editor/{sourceUri}?recipeUri={recipeUri}&autoOptimize={autoOptimize}") {
        fun createRoute(
            sourceUri: String,
            recipeUri: String? = null,
            autoOptimize: Boolean = false
        ): String {
            val encodedSource = java.net.URLEncoder.encode(sourceUri, "UTF-8")
            val params = buildList {
                recipeUri?.let {
                    add("recipeUri=${java.net.URLEncoder.encode(it, "UTF-8")}")
                }
                if (autoOptimize) {
                    add("autoOptimize=true")
                }
            }
            return if (params.isNotEmpty()) {
                "photo_editor/$encodedSource?${params.joinToString("&")}"
            } else {
                "photo_editor/$encodedSource"
            }
        }
    }
}
