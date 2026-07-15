package com.arm.aichat.skill

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Loads SkillDefinition objects from SKILL.md files found in directories
 * or bundled in Android assets.
 *
 * Mirrors the TypeScript `loadSkillsFromDir()` and `parseSkillFile()` from
 * the reference claude-code-from-scratch project.
 */
object SkillLoader {

    private const val TAG = "SkillLoader"
    private const val SKILL_FILE_NAME = "SKILL.md"
    private const val ASSETS_SKILLS_PATH = "skills"

    /**
     * Load skills from a filesystem directory.
     * Each subdirectory should contain a SKILL.md file.
     *
     * @param skillsDir the base directory containing skill subdirectories
     * @param source "user" or "bundled"
     * @param skills map to populate (allows priority-based override)
     */
    fun loadFromDir(
        skillsDir: File,
        source: String,
        skills: MutableMap<String, SkillDefinition>
    ) {
        Log.i(TAG, "loadFromDir: $skillsDir, $source, $skills")
        if (!skillsDir.isDirectory) return

        val entries = skillsDir.listFiles() ?: return
        for (entry in entries) {
            Log.i(TAG, "listFiles: entry=$entry")
            if (!entry.isDirectory) continue
            val skillFile = File(entry, SKILL_FILE_NAME)
            if (!skillFile.exists()) continue

            val skill = parseSkillFile(skillFile, source, entry.absolutePath)
            Log.i(TAG, "skill: skill=$skill")
            if (skill != null) {
                skills[skill.name] = skill
                Log.d(TAG, "Loaded skill '${skill.name}' from ${skillFile.absolutePath}")
            }
        }
    }

    /**
     * Load skills from Android bundled assets (app/src/main/assets/skills/...).
     *
     * Android's AssetManager has no real filesystem; each skill is a directory
     * containing a SKILL.md file. We first list the immediate children of the
     * "skills" asset directory, then try to open each one's SKILL.md file.
     *
     * @param context Android context
     * @param source "bundled"
     * @param skills map to populate
     */
    fun loadFromAssets(
        context: Context,
        source: String,
        skills: MutableMap<String, SkillDefinition>
    ) {
        try {
            // List subdirectories under assets/skills/
            val entries = context.assets.list(ASSETS_SKILLS_PATH) ?: return
            for (entry in entries) {
                // Try to open skills/<entry>/SKILL.md
                val skillAssetPath = "$ASSETS_SKILLS_PATH/$entry/$SKILL_FILE_NAME"
                try {
                    val inputStream = context.assets.open(skillAssetPath)
                    val raw = inputStream.bufferedReader().use { it.readText() }

                    val skill = parseSkillContent(raw, source, null /* no real dir path */)
                    if (skill != null) {
                        skills[skill.name] = skill
                        Log.d(TAG, "Loaded bundled skill '${skill.name}' from assets/$skillAssetPath")
                    }
                } catch (_: IOException) {
                    // Not a valid skill directory — skip
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "No 'skills' directory in assets", e)
        }
    }

    /**
     * Parse a single SKILL.md file into a SkillDefinition.
     */
    fun parseSkillFile(
        file: File,
        source: String,
        skillDir: String
    ): SkillDefinition? {
        return try {
            val raw = file.readText()
            parseSkillContent(raw, source, skillDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill file: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Parse a raw SKILL.md string into a SkillDefinition.
     */
    private fun parseSkillContent(
        raw: String,
        source: String,
        skillDir: String?
    ): SkillDefinition? {
        val result = FrontmatterParser.parse(raw)
        if (result.body.isBlank()) return null

        val meta = result.meta

        // Determine name: from frontmatter or from directory name
        val name = FrontmatterParser.getString(meta, "name")
            ?: skillDir?.substringAfterLast(File.separator)
            ?: return null

        val description = FrontmatterParser.getString(meta, "description") ?: ""
        val whenToUse = FrontmatterParser.getString(meta, "when_to_use", "when-to-use")
        val userInvocable = FrontmatterParser.getBool(meta, "user-invocable", default = true)
        val allowedTools = FrontmatterParser.getList(meta, "allowed-tools")

        val contextStr = FrontmatterParser.getString(meta, "context")
        val context = if (contextStr?.lowercase() == "fork") SkillContext.FORK else SkillContext.INLINE

        return SkillDefinition(
            name = name,
            description = description,
            whenToUse = whenToUse,
            allowedTools = allowedTools,
            userInvocable = userInvocable,
            context = context,
            promptTemplate = result.body,
            skillDir = skillDir,
            source = source,
        )
    }
}
