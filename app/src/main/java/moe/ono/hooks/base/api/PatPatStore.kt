package moe.ono.hooks.base.api

import org.json.JSONObject

object PatPatStore {
    @Volatile var lastCmd: String? = null
    @Volatile var lastText: String? = null
    @Volatile var lastJson: JSONObject? = null
    @Volatile var lastTs: Long = 0L
}