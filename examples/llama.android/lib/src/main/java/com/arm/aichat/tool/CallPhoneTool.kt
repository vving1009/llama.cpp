package com.arm.aichat.tool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 */
class CallPhoneTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "call_phone",
        description = "Place a phone call. Provide EXACTLY ONE of the parameters: " +
            "`phone_number` to dial a raw E.164 number (must start with +country code), " +
            "or `contact_name` to look up a contact via the system Contacts provider " +
            "and dial that contact's first phone number. Prefer `contact_name` when the " +
            "user mentions a person by name.",
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
     * keep follow-up enabled.
     */
    override fun requiresFollowUp(result: String): Boolean = result.startsWith("Error:")

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

        // Resolve the final number to dial.
        val resolved: ResolvedNumber? = if (phoneNumber != null) {
            if (!phoneNumber.startsWith("+")) {
                return "Error: phone_number must include country code starting with + (e.g. +8613800138000)"
            }
            ResolvedNumber(number = phoneNumber, label = phoneNumber)
        } else {
            lookupContactNumber(contactName!!)
                ?: return "Error: No contact found matching name '$contactName', " +
                    "or READ_CONTACTS permission not granted. " +
                    "Please grant the 'Contacts' permission in system Settings > Apps > AiChat."
        }

        // Check CALL_PHONE permission.
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Error: CALL_PHONE permission not granted. Please grant the 'Phone' " +
                "permission in system Settings > Apps > AiChat."
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, "tel:${resolved?.number}".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Calling ${resolved?.label}..."
        } catch (e: SecurityException) {
            "Error: CALL_PHONE permission denied: ${e.message}"
        } catch (e: Exception) {
            "Error: Failed to make call: ${e.message}"
        }
    }

    /**
     * Look up the first phone number for a contact whose display name matches
     * [name] (case-insensitive substring match). Returns null if the contact
     * is not found, READ_CONTACTS permission is not granted, or the query
     * fails for any reason.
     */
    private fun lookupContactNumber(name: String): ResolvedNumber? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            // Case-insensitive substring match on display name.
            val selection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) LIKE ?"
            val selectionArgs = arrayOf("%${name.lowercase()}%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val number = if (numIdx >= 0) cursor.getString(numIdx) else null
                    val matchedName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (number.isNullOrBlank()) null
                    else ResolvedNumber(number = number, label = "${matchedName ?: name} ($number)")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class ResolvedNumber(val number: String, val label: String)
}
