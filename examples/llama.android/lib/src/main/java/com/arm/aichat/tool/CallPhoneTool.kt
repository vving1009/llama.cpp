package com.arm.aichat.tool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.net.toUri

/**
 * 拨打电话工具
 *
 * 支持两种调用方式（二选一）：
 *  - phone_number: 直接传入 E.164 国际号码（必须以 + 开头）
 *  - contact_name: 通过系统 Contacts ContentProvider 查找联系人，
 *                  自动取该联系人的第一个号码
 *
 * 使用 ACTION_CALL + tel: URI 直接发起通话。
 * 需要 [Manifest.permission.CALL_PHONE] 权限；
 * 联系名方式还需要 [Manifest.permission.READ_CONTACTS]。
 * 若未授权，工具返回错误字符串而非崩溃，由 LLM 在下一轮解释给用户。
 *
 * 如果联系人存在多个号码，则列出所有号码让用户选择。
 */
class CallPhoneTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "call_phone",
        description = "Place a phone call. Provide EXACTLY ONE of the parameters: " +
            "`phone_number` to dial a raw E.164 number (must start with +country code), " +
            "or `contact_name` to look up a contact via the system Contacts provider " +
            "and dial that contact's phone number. Prefer `contact_name` when the " +
            "user mentions a person by name. If the contact has multiple numbers, " +
            "the tool will list them and ask you to call again with `phone_number`.",
        parameters = listOf(
            ToolParameter(
                "phone_number", "string",
                "Phone number with country code, e.g. +8613800138000. " +
                    "Mutually exclusive with contact_name.",
                required = false,
            ),
            ToolParameter(
                "contact_name", "string",
                "Contact display name to look up via the system Contacts provider " +
                    "(case-insensitive substring match). Mutually exclusive with phone_number.",
                required = false,
            ),
        ),
        // Default is overridden per-result by requiresFollowUp() below:
        // success => no follow-up; error => need a natural-language explanation.
        requireFollowUp = false,
    )

    /**
     * On a successful call the system in-call UI plus the green
     * "Calling ..." tool-result bubble in chat are enough confirmation;
     * a follow-up "I am now trying to call X" line from the LLM would be
     * redundant. On any error (missing permission, contact not found,
     * dial failure) we DO want the LLM to explain the failure, so we
     * keep follow-up enabled. Also enable follow-up when multiple numbers
     * are found so the LLM can present the options to the user.
     */
    override fun requiresFollowUp(result: String): Boolean =
        result.startsWith("Error:") || result.startsWith("Multiple numbers found for")

    override suspend fun execute(params: Map<String, String>): String {
        val phoneNumber = params["phone_number"]?.trim()?.takeIf { it.isNotEmpty() }
        val contactName = params["contact_name"]?.trim()?.takeIf { it.isNotEmpty() }

        // Exactly one of the two parameters must be provided.
        if (phoneNumber == null && contactName == null) {
            return "Error: Provide exactly one of 'phone_number' or 'contact_name'."
        }
        if (phoneNumber != null && contactName != null) {
            return "Error: Provide only one of 'phone_number' or 'contact_name', not both."
        }

        // --- phone_number path: validate and call directly ---
        if (phoneNumber != null) {
            if (!phoneNumber.startsWith("+")) {
                return "Error: phone_number must include country code starting with + (e.g. +8613800138000)"
            }
            return placeCall(phoneNumber, phoneNumber)
        }

        // --- contact_name path: look up and possibly disambiguate ---
        val numbers = lookupContactNumbers(contactName!!)
            ?: return "Error: READ_CONTACTS permission not granted. " +
                "Please grant the 'Contacts' permission in system Settings > Apps > AiChat."

        if (numbers.isEmpty()) {
            return "Error: No contact found matching name '$contactName'. " +
                "Please grant the 'Contacts' permission in system Settings > Apps > AiChat."
        }

        // Single number → call directly.
        if (numbers.size == 1) {
            return placeCall(numbers[0].number, numbers[0].label)
        }

        // Multiple numbers → list options for the user to choose from.
        val lines = numbers.mapIndexed { i, r ->
            "  ${i + 1}. ${r.label}"
        }
        return "Multiple numbers found for '$contactName':\n" +
            lines.joinToString("\n") +
            "\n\nPlease tell me which number to call, and I'll call it with the phone_number parameter."
    }

    /**
     * Initiate an ACTION_CALL intent. Returns a success or error message.
     */
    private fun placeCall(number: String, label: String): String {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Error: CALL_PHONE permission not granted. Please grant the 'Phone' " +
                "permission in system Settings > Apps > AiChat."
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, "tel:${number}".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Calling $label..."
        } catch (e: SecurityException) {
            "Error: CALL_PHONE permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: Failed to make call: ${e.message}"
        }
    }

    /**
     * Look up all phone numbers for contacts whose display name matches [name]
     * (case-insensitive substring match). Returns null if READ_CONTACTS permission
     * is not granted. Returns an empty list if no contacts match.
     *
     * Results include the contact name and phone type label (Mobile, Home, Work, etc.)
     * for disambiguation.
     */
    private fun lookupContactNumbers(name: String): List<ResolvedNumber>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
            // Case-insensitive substring match on display name.
            val selection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) LIKE ?"
            val selectionArgs = arrayOf("%${name.lowercase()}%")
            val results = mutableListOf<ResolvedNumber>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val number = if (numIdx >= 0) cursor.getString(numIdx) else null
                    val matchedName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val typeCode = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                    if (!number.isNullOrBlank()) {
                        val typeLabel = if (typeIdx >= 0) {
                            ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                context.resources, typeCode, ""
                            )
                        } else ""
                        val label = if (typeLabel.isNullOrBlank()) {
                            "${matchedName ?: name} ($number)"
                        } else {
                            "${matchedName ?: name} - $typeLabel ($number)"
                        }
                        results.add(ResolvedNumber(number = number, label = label))
                    }
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class ResolvedNumber(val number: String, val label: String)
}
