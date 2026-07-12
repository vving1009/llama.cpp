package com.arm.aichat.skill

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for the skills system.
 *
 * These tests run on the JVM (host), not on Android, so they do NOT
 * test Android-specific features (SkillLoader.loadFromAssets,
 * SkillRegistry initialization with Context). Those require a device
 * or emulator. We test the parsing, resolution, and description logic
 * that is pure Kotlin.
 */
class SkillRegistryTest {

    // ─── FrontmatterParser ──────────────────────────────────────

    @Test
    fun `test parse basic frontmatter`() {
        val content = """---
name: translate
description: Translate text
user-invocable: true
---
Translate the following text: $ARGUMENTS"""
        val result = FrontmatterParser.parse(content)
        assertEquals("translate", result.meta["name"])
        assertEquals("Translate text", result.meta["description"])
        assertEquals(true, result.meta["user-invocable"])
        assertEquals("Translate the following text: \$ARGUMENTS", result.body)
    }

    @Test
    fun `test parse no frontmatter`() {
        val content = "Just plain text\nwith no frontmatter"
        val result = FrontmatterParser.parse(content)
        assertTrue(result.meta.isEmpty())
        assertEquals(content, result.body)
    }

    @Test
    fun `test parse missing closing delimiter`() {
        val content = "---\nname: test\nbody text"
        val result = FrontmatterParser.parse(content)
        assertTrue(result.meta.isEmpty())
        assertEquals(content, result.body)
    }

    @Test
    fun `test parse empty lines in frontmatter`() {
        val content = """---
name: test

description: Has blank line
---
body"""
        val result = FrontmatterParser.parse(content)
        assertEquals("test", result.meta["name"])
        assertEquals("Has blank line", result.meta["description"])
        assertEquals("body", result.body)
    }

    @Test
    fun `test parse boolean false value`() {
        val content = """---
name: auto_skill
user-invocable: false
---
Do something"""
        val result = FrontmatterParser.parse(content)
        assertEquals(false, result.meta["user-invocable"])
    }

    @Test
    fun `test parse list value`() {
        val content = """---
name: safe_skill
allowed-tools: [read_file, write_file]
---
Use tools"""
        val result = FrontmatterParser.parse(content)
        val tools = result.meta["allowed-tools"]
        assertTrue(tools is List<*>)
        @Suppress("UNCHECKED_CAST")
        val toolList = tools as List<String>
        assertEquals(listOf("read_file", "write_file"), toolList)
    }

    @Test
    fun `test frontmatter getString helper`() {
        val content = """---
name: test
when_to_use: When testing
when-to-use: Should not be used
---
body"""
        val result = FrontmatterParser.parse(content)
        // when_to_use takes priority (first matching key)
        val value = FrontmatterParser.getString(result.meta, "when_to_use", "when-to-use")
        assertEquals("When testing", value)
    }

    @Test
    fun `test frontmatter getBool default`() {
        val content = """---
name: test
---
body"""
        val result = FrontmatterParser.parse(content)
        assertTrue(FrontmatterParser.getBool(result.meta, "user-invocable", default = true))
        assertFalse(FrontmatterParser.getBool(result.meta, "user-invocable", default = false))
    }

    // ─── SkillRegistry (pure Kotlin logic) ──────────────────────

    @Test
    fun `test buildSkillDescriptions empty`() {
        // We can't instantiate SkillRegistry without Context,
        // but we can test the build logic through a helper.
        // For now, verify that empty skills produce empty descriptions.
        assertEquals("", buildDescriptions(emptyList()))
    }

    @Test
    fun `test buildSkillDescriptions with skills`() {
        val skills = listOf(
            SkillDefinition(
                name = "translate",
                description = "Translate text",
                whenToUse = "When user asks to translate",
                userInvocable = true,
                promptTemplate = "Translate: \$ARGUMENTS",
                source = "bundled",
            ),
            SkillDefinition(
                name = "review",
                description = "Review code",
                whenToUse = "When user asks to review",
                userInvocable = false,
                promptTemplate = "Review: \$ARGUMENTS",
                source = "bundled",
            ),
        )
        val desc = buildDescriptions(skills)
        assertTrue(desc.contains("/translate"))
        assertTrue(desc.contains("Translate text"))
        assertTrue(desc.contains("When user asks to translate"))
        assertTrue(desc.contains("invoke_skill"))
        assertTrue(desc.contains("review"))
        assertFalse(desc.contains("/review")) // auto-only, not user-invocable
    }

    @Test
    fun `test resolvePrompt arguments`() {
        val skill = SkillDefinition(
            name = "test",
            description = "Test",
            promptTemplate = "Hello, \$ARGUMENTS!",
            source = "user",
        )
        val skillDir = "/tmp/skills/test"
        val skillWithDir = skill.copy(skillDir = skillDir)
        val prompt = resolvePrompt(skillWithDir, "World")
        assertEquals("Hello, World!", prompt)
    }

    @Test
    fun `test resolvePrompt claudeDir`() {
        val skill = SkillDefinition(
            name = "test",
            description = "Test",
            promptTemplate = "Dir: \${CLAUDE_SKILL_DIR}",
            skillDir = "/tmp/skills/test",
            source = "user",
        )
        val prompt = resolvePrompt(skill, "")
        assertEquals("Dir: /tmp/skills/test", prompt)
    }

    @Test
    fun `test resolvePrompt both`() {
        val skill = SkillDefinition(
            name = "test",
            description = "Test",
            promptTemplate = "Args: \$ARGUMENTS, Dir: \${CLAUDE_SKILL_DIR}",
            skillDir = "/data/skills/test",
            source = "user",
        )
        val prompt = resolvePrompt(skill, "hello")
        assertEquals("Args: hello, Dir: /data/skills/test", prompt)
    }

    // ─── Helpers (mirror SkillRegistry logic without Context) ───

    private fun buildDescriptions(skills: List<SkillDefinition>): String {
        if (skills.isEmpty()) return ""

        val lines = mutableListOf("# Available Skills", "")
        val invocable = skills.filter { it.userInvocable }
        val autoOnly = skills.filter { !it.userInvocable }

        if (invocable.isNotEmpty()) {
            lines.add("User-invocable skills (type /<name> in chat):")
            for (s in invocable) {
                lines.add("- **/${s.name}**: ${s.description}")
                if (s.whenToUse != null) lines.add("  When to use: ${s.whenToUse}")
            }
            lines.add("")
        }

        if (autoOnly.isNotEmpty()) {
            lines.add("Auto-invocable skills (use the invoke_skill tool when appropriate):")
            for (s in autoOnly) {
                lines.add("- **${s.name}**: ${s.description}")
                if (s.whenToUse != null) lines.add("  When to use: ${s.whenToUse}")
            }
            lines.add("")
        }

        lines.add(
            "To invoke a skill programmatically, use the invoke_skill tool with " +
            "skill_name and optional args."
        )
        return lines.joinToString("\n")
    }

    private fun resolvePrompt(skill: SkillDefinition, args: String): String {
        var prompt = skill.promptTemplate
        prompt = prompt.replace(Regex("\\\$ARGUMENTS|\\$\\{ARGUMENTS}"), args)
        if (skill.skillDir != null) {
            prompt = prompt.replace("\${CLAUDE_SKILL_DIR}", skill.skillDir)
        }
        return prompt
    }
}