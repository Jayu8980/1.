package com.tubepilot.llm.di

import com.tubepilot.llm.LlmProvider
import com.tubepilot.llm.provider.AnthropicProvider
import com.tubepilot.llm.provider.GeminiProvider
import com.tubepilot.llm.provider.OnDeviceProvider
import com.tubepilot.llm.provider.OpenAiProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds @IntoSet abstract fun anthropic(impl: AnthropicProvider): LlmProvider
    @Binds @IntoSet abstract fun openAi(impl: OpenAiProvider): LlmProvider
    @Binds @IntoSet abstract fun gemini(impl: GeminiProvider): LlmProvider
    @Binds @IntoSet abstract fun onDevice(impl: OnDeviceProvider): LlmProvider
}
