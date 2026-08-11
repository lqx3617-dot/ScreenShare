package com.screenshare

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * 信令管理器：把 SDP + ICE 候选编码为 JSON 字符串。
 *
 * 两种传输方式：
 * - 扫码模式：把 JSON 编码成二维码，扫码后解码还原，本地无服务器直连 P2P
 * - 服务器中继：把 JSON 通过 WebSocket 信令服务器转发（signalMode）
 *
 * 二维码内容格式（JSON）：
 * {
 *   "role": "host" | "join",
 *   "sdp": { "type": "offer"|"answer", "sdp": "..." },
 *   "ice": [ { "id":..., "label":..., "candidate":... }, ... ]
 * }
 */
object SignalManager {
    private const val TAG = "SignalManager"

    /**
     * 将 Host 的 Offer + ICE 候选编码为二维码字符串
     */
    fun encodeOffer(sdp: SessionDescription, candidates: List<IceCandidate>): String {
        val json = JSONObject().apply {
            put("role", "host")
            put("sdp", JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp.description)
            })
            put("ice", JSONArray().apply {
                candidates.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.sdpMid)
                        put("label", c.sdpMLineIndex)
                        put("candidate", c.sdp)
                    })
                }
            })
        }
        val result = json.toString()
        Log.d(TAG, "Offer 二维码大小: ${result.length} bytes")
        return result
    }

    /**
     * 将 Join 的 Answer + ICE 候选编码为字符串
     * （Join 端生成二维码给 Host 扫）
     */
    fun encodeAnswer(sdp: SessionDescription, candidates: List<IceCandidate>): String {
        val json = JSONObject().apply {
            put("role", "join")
            put("sdp", JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp.description)
            })
            put("ice", JSONArray().apply {
                candidates.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.sdpMid)
                        put("label", c.sdpMLineIndex)
                        put("candidate", c.sdp)
                    })
                }
            })
        }
        return json.toString()
    }

    /**
     * Trickle ICE：将单个候选编码为独立消息（SDP 之后增量发送）
     */
    fun encodeCandidate(c: IceCandidate): String {
        return JSONObject().apply {
            put("type", "candidate")
            put("id", c.sdpMid)
            put("label", c.sdpMLineIndex)
            put("candidate", c.sdp)
        }.toString()
    }

    /**
     * 解码增量候选消息；非候选消息返回 null
     */
    fun decodeCandidate(raw: String): IceCandidate? {
        return try {
            val json = JSONObject(raw)
            if (json.optString("type") != "candidate") return null
            IceCandidate(
                json.getString("id"),
                json.getInt("label"),
                json.getString("candidate")
            )
        } catch (e: Exception) {
            Log.w(TAG, "候选解码失败: ${e.message}")
            null
        }
    }

    /**
     * 解码二维码内容，返回 Pair<SessionDescription, List<IceCandidate>>
     * 或 null（数据无效）
     */
    fun decode(raw: String): Pair<SessionDescription, List<IceCandidate>>? {
        return try {
            val json = JSONObject(raw)
            val role = json.getString("role")
            val sdpObj = json.getJSONObject("sdp")
            val type = when (sdpObj.getString("type")) {
                "offer" -> SessionDescription.Type.OFFER
                "answer" -> SessionDescription.Type.ANSWER
                else -> return null
            }
            val sdp = SessionDescription(type, sdpObj.getString("sdp"))

            val candidates = mutableListOf<IceCandidate>()
            val iceArray = json.optJSONArray("ice") ?: JSONArray()
            for (i in 0 until iceArray.length()) {
                val iceObj = iceArray.getJSONObject(i)
                candidates.add(
                    IceCandidate(
                        iceObj.getString("id"),
                        iceObj.getInt("label"),
                        iceObj.getString("candidate")
                    )
                )
            }

            Log.d(TAG, "解码成功: role=$role, type=$type, ice=${candidates.size}")
            Pair(sdp, candidates)
        } catch (e: Exception) {
            Log.e(TAG, "解码失败: ${e.message}")
            null
        }
    }
}