package com.tubepilot.agents

import com.tubepilot.agents.core.Agent
import com.tubepilot.core.model.AgentRole
import javax.inject.Inject
import javax.inject.Singleton

/** The whole team, for the Agent Team screen and the pipeline's retry policies. */
@Singleton
class AgentTeam @Inject constructor(
    val orchestrator: OrchestratorAgent,
    val trendResearcher: TrendResearcherAgent,
    val scriptwriter: ScriptwriterAgent,
    val seo: SeoSpecialistAgent,
    val thumbnails: ThumbnailDesignerAgent,
    val voice: VoiceAudioAgent,
    val editor: VideoEditorAgent,
    val uploader: UploadSchedulerAgent,
    val community: CommunityManagerAgent,
    val analytics: AnalyticsAgent,
    val compliance: ComplianceAgent,
    val qa: QaAgent,
) {
    val all: List<Agent> = listOf(orchestrator, trendResearcher, scriptwriter, seo, thumbnails, voice, editor, uploader, community, analytics, compliance, qa)

    fun byRole(role: AgentRole): Agent = all.first { it.role == role }
}
