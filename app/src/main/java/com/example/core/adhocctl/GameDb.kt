package com.example.core.adhocctl

import com.example.core.common.LogCategory
import com.example.core.common.ServerLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeMap
import java.util.TreeSet

data class GameDb(
    val crosslinks: Map<String, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val codQuirkGames: Set<String> = emptySet()
)

data class GameDbParseResult(
    val isValid: Boolean,
    val gameDb: GameDb,
    val errorMessage: String? = null
)

object GameDbCodec {
    fun parseFromFile(file: File): GameDbParseResult {
        if (!file.exists()) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: failed reading json file ${file.absolutePath}")
            return GameDbParseResult(false, GameDb(), "File not found")
        }
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: failed reading json file ${file.absolutePath}")
            return GameDbParseResult(false, GameDb(), e.message)
        }
        if (raw.isEmpty()) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: failed reading json file ${file.absolutePath}")
            return GameDbParseResult(false, GameDb(), "Empty file")
        }
        return parseFromJsonString(raw)
    }

    fun parseFromJsonString(rawJson: String): GameDbParseResult {
        val parsed = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: exception occurred while parsing JSON (${e.message})")
            return GameDbParseResult(false, GameDb(), "Invalid JSON: ${e.message}")
        }

        if (!parsed.has("crosslinks")) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: crosslinks not found in json")
            return GameDbParseResult(false, GameDb(), "Missing 'crosslinks' object")
        }
        val crosslinksObj = parsed.opt("crosslinks")
        if (crosslinksObj !is JSONObject) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: crosslinks is not an object")
            return GameDbParseResult(false, GameDb(), "'crosslinks' must be a JSON object")
        }

        if (!parsed.has("names")) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: names not found in json")
            return GameDbParseResult(false, GameDb(), "Missing 'names' object")
        }
        val namesObj = parsed.opt("names")
        if (namesObj !is JSONObject) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: names is not an object")
            return GameDbParseResult(false, GameDb(), "'names' must be a JSON object")
        }

        if (!parsed.has("cod_quirk_games")) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: cod_quirk_games not found in json")
            return GameDbParseResult(false, GameDb(), "Missing 'cod_quirk_games' array")
        }
        val codQuirksArr = parsed.opt("cod_quirk_games")
        if (codQuirksArr !is JSONArray) {
            ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: cod_quirk_games is not an array")
            return GameDbParseResult(false, GameDb(), "'cod_quirk_games' must be a JSON array")
        }

        val crosslinks = TreeMap<String, String>()
        val crossKeys = crosslinksObj.keys()
        while (crossKeys.hasNext()) {
            val key = crossKeys.next()
            val value = crosslinksObj.opt(key)
            if (value !is String) {
                ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: bad non string value on crosslink entry $key")
                return GameDbParseResult(false, GameDb(), "Crosslink entry '$key' must map to a string")
            }
            crosslinks[key] = value
        }

        val names = TreeMap<String, String>()
        val nameKeys = namesObj.keys()
        while (nameKeys.hasNext()) {
            val key = nameKeys.next()
            val value = namesObj.opt(key)
            if (value !is String) {
                ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: bad non string value on name entry $key")
                return GameDbParseResult(false, GameDb(), "Name entry '$key' must map to a string")
            }
            names[key] = value
        }

        for ((fromId, toId) in crosslinks) {
            if (crosslinks.containsKey(toId)) {
                val msg = "Crosslink $fromId -> $toId links an existing crosslink entry; recursive crosslinking is not supported"
                ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: $msg")
                return GameDbParseResult(false, GameDb(), msg)
            }
        }

        val codQuirks = TreeSet<String>()
        for (i in 0 until codQuirksArr.length()) {
            val item = codQuirksArr.opt(i)
            if (item !is String) {
                ServerLogger.error(LogCategory.CONFIG, "parse_game_db_from_json: bad non string value in cod_quirk_games")
                return GameDbParseResult(false, GameDb(), "All 'cod_quirk_games' entries must be strings")
            }
            codQuirks.add(item)
        }

        return GameDbParseResult(true, GameDb(crosslinks, names, codQuirks))
    }

    fun toJsonString(gameDb: GameDb): String {
        val root = JSONObject()
        val namesObj = JSONObject()
        for ((k, v) in TreeMap(gameDb.names)) {
            namesObj.put(k, v)
        }
        val crossObj = JSONObject()
        for ((k, v) in TreeMap(gameDb.crosslinks)) {
            crossObj.put(k, v)
        }
        val quirksArr = JSONArray()
        for (item in TreeSet(gameDb.codQuirkGames)) {
            quirksArr.put(item)
        }
        root.put("names", namesObj)
        root.put("crosslinks", crossObj)
        root.put("cod_quirk_games", quirksArr)
        return root.toString(4) + "\n"
    }

    fun gameDbSame(left: GameDb, right: GameDb): Boolean {
        return left.crosslinks == right.crosslinks &&
            left.names == right.names &&
            left.codQuirkGames == right.codQuirkGames
    }
}
