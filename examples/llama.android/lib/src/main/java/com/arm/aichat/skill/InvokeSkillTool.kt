package com.arm.aichat.skill

import com.arm.aichat.tool.Tool
import com.arm.aichat.tool.ToolDefinition
import com.arm.aichat.tool.ToolParameter

/**
 * Tool that invokes a registered skill by name.
 *
 * Mirrors the `skill` tool definition from the reference claude-code-from-scratch
 * project (`tools.ts`). When the LLM calls this tool, it returns the skill's
 * resolved prompt template (with $ARGUMENTS replaced), which the LLM then
 * carries out in the next turn.
 *
 * The returned string format is:
 *   [Skill "name" activated]
 *   {resolved skill prompt}
 */
class InvokeSkillTool(private val skillRegistry: SkillRegistry) : Tool {

    override val definition = ToolDefinition(
        name = "invoke_skill",
        description = "Invoke a registered skill by name. Returns the skill's prompt template " +
            $$"with $ARGUMENTS resolved. Use this when a skill's when_to_use condition matches " +
            "the user's request.",
        parameters = listOf(
            ToolParameter(
                name = "skill_name",
                type = "string",
                description = "The name of the skill to invoke (e.g. 'translate', 'summarize')",
            ),
            ToolParameter(
                name = "args",
                type = "string",
                description = "Optional arguments to pass to the skill",
                required = false,
            ),
        ),
    )

    override suspend fun execute(params: Map<String, String>): String {
        val skillName = params["skill_name"] ?: return "Error: Missing 'skill_name' parameter"
        val args = params["args"] ?: ""

        val resolved = skillRegistry.executeSkill(skillName, args)
        if (resolved != null) {
            return "[Skill \"${skillName}\" activated]\n\n${resolved.prompt}"
        }

        val available = skillRegistry.getAll().joinToString(", ") { it.name }
        return "Error: Unknown skill '$skillName'. Available skills: $available"
    }

    /**
     * Skills always need a follow-up turn so the LLM can carry out the
     * skill's instructions (e.g. translate, summarize, review).
     */
    override fun requiresFollowUp(result: String): Boolean = true
}
