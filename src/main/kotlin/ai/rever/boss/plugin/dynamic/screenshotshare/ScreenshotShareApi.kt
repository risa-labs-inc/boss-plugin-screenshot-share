package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Thin wrapper around [PluginContext.supabaseDataProvider] for the RPCs added in
 * BossConsole/supabase/migrations/20260824000000_screenshot_shares.sql.
 *
 * NOTE: `SupabaseDataProvider.rpc`'s exact parameter type lives in boss-plugin-api,
 * which isn't checked out in this workspace (it's a git submodule pointing at a
 * private repo). This assumes `rpc(function: String, parameters: String): Result<String>`
 * per docs/plugin-api.md's summary ("rpc(function, parameters): Result<String>") --
 * verify against the real interface and adjust only this file if it differs.
 */
class ScreenshotShareApi(private val context: PluginContext) {

    private suspend fun rpc(function: String, params: JsonObject): Result<JsonObject> {
        val provider = context.supabaseDataProvider
            ?: return Result.failure(IllegalStateException("Supabase is not available to this plugin"))
        return provider.rpc(function, params.toString()).mapCatching { raw ->
            Json.parseToJsonElement(raw).jsonObject
        }
    }

    private fun requireSuccess(obj: JsonObject): JsonObject {
        val ok = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Request failed")
        return obj
    }

    /** Required string field, e.g. `o.str("user_id")` for `o["user_id"]!!.jsonPrimitive.content`. */
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    suspend fun listShareableRecipients(query: String? = null): Result<List<Recipient>> =
        rpc(
            "list_shareable_recipients",
            buildJsonObject {
                put("p_query", query)
                put("p_limit", 50)
            },
        ).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                Recipient(
                    userId = o.str("user_id"),
                    email = o.str("email"),
                    orgId = o.str("org_id"),
                    orgName = o.str("org_name"),
                )
            }
        }

    suspend fun shareScreenshot(
        recipientId: String,
        imageBase64: String,
        mimeType: String,
        width: Int,
        height: Int,
        note: String?,
    ): Result<String> =
        rpc(
            "share_screenshot",
            buildJsonObject {
                put("p_recipient_id", recipientId)
                put("p_image_base64", imageBase64)
                put("p_mime_type", mimeType)
                put("p_width", width)
                put("p_height", height)
                put("p_note", note)
            },
        ).mapCatching { obj ->
            requireSuccess(obj).strOrNull("share_id") ?: error("Missing share_id in response")
        }

    suspend fun listReceived(onlyUnread: Boolean = false): Result<List<ReceivedScreenshot>> =
        rpc(
            "list_received_screenshots",
            buildJsonObject {
                put("p_only_unread", onlyUnread)
                put("p_limit", 50)
            },
        ).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                ReceivedScreenshot(
                    id = o.str("id"),
                    senderId = o.str("sender_id"),
                    senderEmail = o.str("sender_email"),
                    note = o.strOrNull("note"),
                    width = o.intOrNull("width"),
                    height = o.intOrNull("height"),
                    createdAt = o.str("created_at"),
                    readAt = o.strOrNull("read_at"),
                )
            }
        }

    suspend fun listSent(): Result<List<SentScreenshot>> =
        rpc("list_sent_screenshots", buildJsonObject { put("p_limit", 50) }).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                SentScreenshot(
                    id = o.str("id"),
                    recipientId = o.str("recipient_id"),
                    recipientEmail = o.str("recipient_email"),
                    note = o.strOrNull("note"),
                    width = o.intOrNull("width"),
                    height = o.intOrNull("height"),
                    createdAt = o.str("created_at"),
                    readAt = o.strOrNull("read_at"),
                )
            }
        }

    /** Returns (base64Image, mimeType). Marks the share read server-side as a side effect. */
    suspend fun getImage(shareId: String): Result<Pair<String, String>> =
        rpc("get_screenshot_image", buildJsonObject { put("p_share_id", shareId) }).mapCatching { obj ->
            val o = requireSuccess(obj)
            o.str("image_base64") to o.str("mime_type")
        }
}
