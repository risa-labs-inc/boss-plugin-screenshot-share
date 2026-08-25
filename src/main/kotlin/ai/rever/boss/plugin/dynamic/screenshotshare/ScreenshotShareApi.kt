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
                    userId = o["user_id"]!!.jsonPrimitive.content,
                    email = o["email"]!!.jsonPrimitive.content,
                    orgId = o["org_id"]!!.jsonPrimitive.content,
                    orgName = o["org_name"]!!.jsonPrimitive.content,
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
            requireSuccess(obj)["share_id"]?.jsonPrimitive?.content ?: error("Missing share_id in response")
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
                    id = o["id"]!!.jsonPrimitive.content,
                    senderId = o["sender_id"]!!.jsonPrimitive.content,
                    senderEmail = o["sender_email"]!!.jsonPrimitive.content,
                    note = o["note"]?.jsonPrimitive?.contentOrNull,
                    width = o["width"]?.jsonPrimitive?.intOrNull,
                    height = o["height"]?.jsonPrimitive?.intOrNull,
                    createdAt = o["created_at"]!!.jsonPrimitive.content,
                    readAt = o["read_at"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }

    suspend fun listSent(): Result<List<SentScreenshot>> =
        rpc("list_sent_screenshots", buildJsonObject { put("p_limit", 50) }).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                SentScreenshot(
                    id = o["id"]!!.jsonPrimitive.content,
                    recipientId = o["recipient_id"]!!.jsonPrimitive.content,
                    recipientEmail = o["recipient_email"]!!.jsonPrimitive.content,
                    note = o["note"]?.jsonPrimitive?.contentOrNull,
                    width = o["width"]?.jsonPrimitive?.intOrNull,
                    height = o["height"]?.jsonPrimitive?.intOrNull,
                    createdAt = o["created_at"]!!.jsonPrimitive.content,
                    readAt = o["read_at"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }

    /** Returns (base64Image, mimeType). Marks the share read server-side as a side effect. */
    suspend fun getImage(shareId: String): Result<Pair<String, String>> =
        rpc("get_screenshot_image", buildJsonObject { put("p_share_id", shareId) }).mapCatching { obj ->
            val o = requireSuccess(obj)
            val base64 = o["image_base64"]!!.jsonPrimitive.content
            val mime = o["mime_type"]!!.jsonPrimitive.content
            base64 to mime
        }
}
