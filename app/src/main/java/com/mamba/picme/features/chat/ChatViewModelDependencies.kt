package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase

class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val mediaFeedbackRepository: MediaFeedbackRepository,
    val picMeAuthClient: PoLangAuthClient,
    val getGallerySummaryUseCase: GetGallerySummaryUseCase,
    val queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
    val startTagScanUseCase: StartTagScanUseCase,
    val chatImageRenderer: ChatImageRenderer? = null
)
