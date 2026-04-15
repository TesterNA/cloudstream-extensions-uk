package com.lagradost.models

data class TranslationResponse(
    val translations: List<Translation>
)

data class Translation(
    val id: Int,
    val name: String,
    val synonyms: List<String>?,
    val isSub: Boolean,
    val studios: List<TranslationStudio>,
    val player: List<TranslationPlayer>
)

data class TranslationStudio(
    val slug: String,
    val name: String,
    val description: String?,
    val team: String?,
    val telegram: String?,
    val youtube: String?,
    val patreon: String?,
    val buymeacoffee: String?,
    val avatar: TranslationAvatar?
)

data class TranslationAvatar(
    val id: Int,
    val original: String,
    val preview: String
)

data class TranslationPlayer(
    val name: String,
    val id: Int,
    val episodesCount: Int
)
