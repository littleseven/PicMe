package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.context.MediaAsset

enum class GroupingMode {
    NONE,
    DATE,
    FACE,
    PERSON,
    LANDSCAPE,
    SWIMWEAR,
    SEXY,
    LOCATION
}

enum class GroupTitleType {
    NONE,
    DATE,
    WITH_FACES,
    NO_FACES,
    PERSON,
    LANDSCAPE,
    SWIMWEAR,
    SEXY,
    SEARCH,
    LOCATION,
    NO_LOCATION
}

data class GroupedMedia(
    val titleType: GroupTitleType,
    val titleValue: String,
    val items: List<MediaAsset>
)

