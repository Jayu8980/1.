package com.tubepilot.agents

import com.tubepilot.agents.contracts.BriefRequest
import com.tubepilot.agents.contracts.IdeaList
import com.tubepilot.agents.contracts.IdeaRequest
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.ResearchBrief
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.util.TubeJson
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Finds topics and turns a chosen idea into a research brief. Picks its skills by tool calling. */
@Singleton
class TrendResearcherAgent @Inject constructor(runtime: AgentRuntime) : Agent(AgentRole.TREND_RESEARCHER, runtime) {

    override val rolePrompt = """
        You are the Trend Researcher of a YouTube channel team. You find topics the channel's audience
        is actively looking for, judge how crowded each topic already is, and how well it fits the
        niche. You ground every judgement in data from your skills (trend_scan for channel analytics,
        search demand and competitors; keyword_research for search phrasing) and say when data is missing.
    """.trimIndent()
    override val memoryFocus = setOf(LessonCategory.TOPIC, LessonCategory.FORMAT)
    override val skillIds = listOf("trend_scan", "keyword_research")

    suspend fun findIdeas(request: IdeaRequest): IdeaList = task("Find ${request.count} ideas", null) {
        val result = runtime.reason(
            role = role,
            rolePrompt = rolePrompt,
            focus = memoryFocus,
            task = "Find ${request.count} strong, original video ideas for the coming week. Start with trend_scan. " +
                "Use keyword_research only for the most promising idea if it helps sharpen keywords. " +
                "Return the best ideas ranked, keeping the scores the skills produced.",
            input = TubeJson.encodeToJsonElement(IdeaRequest.serializer(), request).jsonObject,
            output = IdeaList.serializer(),
            outputExample = IdeaList(listOf(TopicIdea("5 budget phone camera tricks", "Show results side by side", listOf("phone camera tips"), VideoFormat.SHORT, "Tips", 7.5, 4.0, 9.0, 7.6, "Strong search demand, few Hindi creators")), "notes"),
            projectId = null,
            includePerformance = true,
        )
        // Re-score deterministically in case the model edited numbers.
        result.copy(ideas = result.ideas.map { idea ->
            val total = 0.4 * idea.demandScore.coerceIn(0.0, 10.0) + 0.25 * (10 - idea.competitionScore.coerceIn(0.0, 10.0)) + 0.35 * idea.nicheFitScore.coerceIn(0.0, 10.0)
            idea.copy(totalScore = Math.round(total * 100) / 100.0)
        }.sortedByDescending { it.totalScore }.take(request.count))
    }

    suspend fun brief(request: BriefRequest, projectId: String): ResearchBrief = task("Research '${request.idea.title}'", projectId) {
        runtime.reason(
            role = role,
            rolePrompt = rolePrompt,
            focus = memoryFocus,
            task = "Write a research brief for this video. Use keyword_research to find how viewers search for it. " +
                "List the questions viewers have, the key points to cover, what competitors miss (the unique angle), " +
                "and any facts the writer must double-check. Write in ${request.language.displayName}-friendly terms.",
            input = TubeJson.encodeToJsonElement(BriefRequest.serializer(), request).jsonObject,
            output = ResearchBrief.serializer(),
            outputExample = ResearchBrief(
                idea = request.idea,
                targetViewer = "Students buying their first phone",
                audienceQuestions = listOf("Which setting matters most?"),
                keyPoints = listOf("Clean the lens", "Use HDR outdoors"),
                keywords = listOf("phone camera tips"),
                competitorNotes = listOf("Most videos skip low-light"),
                uniqueAngle = "Test every tip on a ₹10k phone",
                factsToVerify = listOf("Default HDR behaviour on Android 16"),
            ),
            projectId = projectId,
        ).let { it.copy(idea = request.idea) }
    }
}
