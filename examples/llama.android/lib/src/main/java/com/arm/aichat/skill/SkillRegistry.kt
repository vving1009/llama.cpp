package com.arm.aichat.skill

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Central registry for skills in the Android agent system.
 *
 * Discovers skills from two sources (in priority order):
 * 1. User-level: `filesDir/skills/` — higher priority, overrides bundled
 * 2. Bundled: `assets/skills/` — shipped with the APK
 *
 * Supports:
 * - Skill discovery & caching
 * - Lookup by name
 * - Prompt template resolution ($ARGUMENTS, ${CLAUDE_SKILL_DIR})
 * - System prompt section generation
 * - Skill execution (resolve + return prompt)
 *
 * Mirrors the TypeScript `discoverSkills()`, `getSkillByName()`,
 * `resolveSkillPrompt()`, `executeSkill()`, and `buildSkillDescriptions()`
 * from the reference claude-code-from-scratch project.
 */
class SkillRegistry(private val context: Context) {

    companion object {
        private const val TAG = "SkillRegistry"
        private const val SKILLS_DIR_NAME = "skills"
    }

    private var skills: List<SkillDefinition> = emptyList()
    private var initialized = false

    /**
     * Initialize the registry: discover and cache all skills.
     * Must be called once (typically during AgentLoop.initialize).
     *
     * @param forceRefresh if true, re-scans from disk even if already initialized
     */
    fun initialize(forceRefresh: Boolean = false) {
        if (initialized && !forceRefresh) return

        val skillMap = linkedMapOf<String, SkillDefinition>()

        // 1. Bundled (lowest priority — loaded first, overridden by user)
        SkillLoader.loadFromAssets(context, "bundled", skillMap)

        // 2. User-level (higher priority — loaded last, overwrites bundled)
        val userDir = getUserSkillsDir()
        SkillLoader.loadFromDir(userDir, "user", skillMap)

        skills = skillMap.values.toList()
        initialized = true

        if (skills.isNotEmpty()) {
            Log.i(TAG, "Discovered ${skills.size} skills: ${skills.joinToString(", ") { it.name }}")
        } else {
            Log.d(TAG, "No skills discovered")
        }
    }

    /**
     * Re-scan skills from disk (useful after adding new SKILL.md files at runtime).
     */
    fun refresh() {
        initialize(forceRefresh = true)
    }

    /**
     * Returns all discovered skills.
     */
    fun getAll(): List<SkillDefinition> = skills

    /**
     * Look up a skill by name (case-sensitive).
     */
    fun getByName(name: String): SkillDefinition? = skills.find { it.name == name }

    /**
     * Build the "# Available Skills" section for the system prompt.
     * Mirrors the TypeScript `buildSkillDescriptions()`.
     */
    fun buildSkillDescriptions(): String {
        if (skills.isEmpty()) return ""

        val lines = mutableListOf("# Available Skills", "")
        val invocable = skills.filter { it.userInvocable }
        val autoOnly = skills.filter { !it.userInvocable }

        if (invocable.isNotEmpty()) {
            lines.add("User-invocable skills (type /<name> in chat):")
            for (s in invocable) {
                lines.add("- **/${s.name}**: ${s.description}")
                if (s.whenToUse != null) {
                    lines.add("  When to use: ${s.whenToUse}")
                }
            }
            lines.add("")
        }

        if (autoOnly.isNotEmpty()) {
            lines.add("Auto-invocable skills (use the invoke_skill tool when appropriate):")
            for (s in autoOnly) {
                lines.add("- **${s.name}**: ${s.description}")
                if (s.whenToUse != null) {
                    lines.add("  When to use: ${s.whenToUse}")
                }
            }
            lines.add("")
        }

        lines.add(
            "To invoke a skill programmatically, use the invoke_skill tool with " +
            "skill_name and optional args."
        )
        return lines.joinToString("\n")
    }

    /**
     * Resolve a skill's prompt template with the given arguments.
     * Replaces $ARGUMENTS and ${CLAUDE_SKILL_DIR}.
     */
    fun resolvePrompt(skill: SkillDefinition, args: String): String {
        var prompt = skill.promptTemplate

        // Replace ${ARGUMENTS} first (longer match first), then $ARGUMENTS
        prompt = prompt.replace("\${ARGUMENTS}", args)
        prompt = prompt.replace("\$ARGUMENTS", args)

        // Replace ${CLAUDE_SKILL_DIR}
        if (skill.skillDir != null) {
            prompt = prompt.replace("\${CLAUDE_SKILL_DIR}", skill.skillDir)
        }

        return prompt
    }

    /**
     * Execute a skill by name: resolve the prompt template with the given args.
     *
     * @param skillName name of the skill to execute
     * @param args arguments to pass to the skill (replaces $ARGUMENTS)
     * @return a ResolvedSkill with the resolved prompt, or null if skill not found
     */
    fun executeSkill(skillName: String, args: String = ""): ResolvedSkill? {
        val skill = getByName(skillName) ?: return null
        val prompt = resolvePrompt(skill, args)
        return ResolvedSkill(skill = skill, prompt = prompt, args = args)
    }

    /**
     * Get the user-level skills directory (filesDir/skills/).
     * Creates the directory if it doesn't exist.
     */
    private fun getUserSkillsDir(): File {
        val dir = File(context.getExternalFilesDir(null), SKILLS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
