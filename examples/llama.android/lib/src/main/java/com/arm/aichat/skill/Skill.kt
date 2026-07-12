package com.arm.aichat.skill

/**
 * Skill definition — mirrors the TypeScript SkillDefinition interface from
 * the reference claude-code-from-scratch project.
 *
 * A skill is a reusable prompt template stored in a SKILL.md file with YAML
 * frontmatter. Skills can be invoked by the user (type /name in chat) or by
 * the model (via the invoke_skill tool).
 */
data class SkillDefinition(
    /** Unique name for this skill (e.g. "translate", "summarize"). */
    val name: String,

    /** Short description shown in the system prompt. */
    val description: String,

    /** Guidance for the model on when to auto-invoke this skill. */
    val whenToUse: String? = null,

    /** Optional whitelist of tools this skill is allowed to use. */
    val allowedTools: List<String>? = null,

    /** If false, only the model can invoke this skill (no /name in chat). */
    val userInvocable: Boolean = true,

    /** Execution mode: inline (inject prompt) or fork (sub-agent — not implemented on Android). */
    val context: SkillContext = SkillContext.INLINE,

    /** The markdown prompt template body (after frontmatter). */
    val promptTemplate: String,

    /** Absolute path to the skill directory, or null for bundled skills. */
    val skillDir: String? = null,

    /** Source of this skill: "user" (filesDir) or "bundled" (assets). */
    val source: String = "user",
)

enum class SkillContext {
    /** Inject the resolved prompt into the current conversation. */
    INLINE,

    /** Run in a sub-agent (not implemented in this Android port). */
    FORK,
}

/**
 * A resolved skill ready for execution.
 */
data class ResolvedSkill(
    val skill: SkillDefinition,
    /** The prompt template with $ARGUMENTS and ${CLAUDE_SKILL_DIR} replaced. */
    val prompt: String,
    val args: String,
)

/**
 * Result of parsing a SKILL.md file's frontmatter.
 */
data class FrontmatterResult(
    val meta: Map<String, Any>,
    val body: String,
)