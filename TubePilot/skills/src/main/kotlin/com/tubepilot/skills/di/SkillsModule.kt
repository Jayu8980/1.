package com.tubepilot.skills.di

import com.tubepilot.skills.AssetSkillSource
import com.tubepilot.skills.BuiltInSkillSource
import com.tubepilot.skills.PromptSkillExecutor
import com.tubepilot.skills.SkillExecutor
import com.tubepilot.skills.builtin.AnalyticsReportExecutor
import com.tubepilot.skills.builtin.CommentTriageExecutor
import com.tubepilot.skills.builtin.ContentCalendarExecutor
import com.tubepilot.skills.builtin.HookGeneratorExecutor
import com.tubepilot.skills.builtin.KeywordResearchExecutor
import com.tubepilot.skills.builtin.PolicyCheckExecutor
import com.tubepilot.skills.builtin.ReplyDrafterExecutor
import com.tubepilot.skills.builtin.RepurposeExecutor
import com.tubepilot.skills.builtin.ScriptWriterExecutor
import com.tubepilot.skills.builtin.ShortsCutterExecutor
import com.tubepilot.skills.builtin.SubtitleGeneratorExecutor
import com.tubepilot.skills.builtin.ThumbnailMakerExecutor
import com.tubepilot.skills.builtin.TitleAbTestExecutor
import com.tubepilot.skills.builtin.TrendScanExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/** Executor ids here must match the "executor" field in each built-in skill.json. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SkillsModule {
    @Binds abstract fun builtIns(impl: AssetSkillSource): BuiltInSkillSource

    @Binds @IntoMap @StringKey("prompt") abstract fun prompt(impl: PromptSkillExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("trend_scan") abstract fun trendScan(impl: TrendScanExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("keyword_research") abstract fun keywordResearch(impl: KeywordResearchExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("hook_generator") abstract fun hookGenerator(impl: HookGeneratorExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("script_writer") abstract fun scriptWriter(impl: ScriptWriterExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("shorts_cutter") abstract fun shortsCutter(impl: ShortsCutterExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("subtitle_generator") abstract fun subtitleGenerator(impl: SubtitleGeneratorExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("thumbnail_maker") abstract fun thumbnailMaker(impl: ThumbnailMakerExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("title_ab_test") abstract fun titleAbTest(impl: TitleAbTestExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("comment_triage") abstract fun commentTriage(impl: CommentTriageExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("reply_drafter") abstract fun replyDrafter(impl: ReplyDrafterExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("analytics_report") abstract fun analyticsReport(impl: AnalyticsReportExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("repurpose_content") abstract fun repurpose(impl: RepurposeExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("content_calendar") abstract fun contentCalendar(impl: ContentCalendarExecutor): SkillExecutor
    @Binds @IntoMap @StringKey("policy_check") abstract fun policyCheck(impl: PolicyCheckExecutor): SkillExecutor
}
