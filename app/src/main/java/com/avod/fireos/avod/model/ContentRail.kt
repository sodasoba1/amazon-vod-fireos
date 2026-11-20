package com.avod.fireos.avod.model

data class ContentRail(
    val headerText: String,
    val items: List<ContentItem>,
    val collectionId: String = "",
    val paginationParams: String = ""
)
