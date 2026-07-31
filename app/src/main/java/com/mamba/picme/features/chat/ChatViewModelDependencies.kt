package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.DiagClient
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import com.mamba.picme.domain.usecase.SaveChatEditResultUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase

@Suppress("LongParameterList") // 待重构：依赖容器，考虑分组或 builder
class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val mediaFeedbackRepository: MediaFeedbackRepository,
    val mediaRepository: MediaRepository,
    val picMeAuthClient: PoLangAuthClient,
    val diagClient: DiagClient = DiagClient(),
    val claudeChatClient: ClaudeChatClient = ClaudeChatClient(),
    val getGallerySummaryUseCase: GetGallerySummaryUseCase,
    val queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
    val startTagScanUseCase: StartTagScanUseCase,
    val personDao: PersonDao,
    val controlledVocab: ControlledVocab,
    val chatEditStateHolder: ChatEditStateHolder,
    val chatEditProcessor: ChatEditProcessor,
    val chatImageRenderer: ChatImageRenderer? = null,
    val chatImageStore: ChatImageStore,
    val saveChatEditResultUseCase: SaveChatEditResultUseCase
)
