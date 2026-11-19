package app.drivr.places

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Home, work and the last few destinations, kept on the phone only. */
data class SavedPlaces(
    val home: Place? = null,
    val work: Place? = null,
    /** Most recent first, no duplicates, at most [MAX_RECENT]. */
    val recent: List<Place> = emptyList(),
) {
  /** [place] at the front of the recents (moved there if it was already in). */
  fun withRecent(place: Place): SavedPlaces =
      copy(recent = (listOf(place) + recent.filterNot { it.sameSpot(place) }).take(MAX_RECENT))

  companion object {
    const val MAX_RECENT = 8
  }
}

/** Same place: same OSM object, or (for dropped pins and addresses) within ~30 m. */
fun Place.sameSpot(other: Place): Boolean =
    (id == other.id && !id.startsWith("pin/")) ||
        (kotlin.math.abs(lat - other.lat) < 0.0003 && kotlin.math.abs(lon - other.lon) < 0.0004 && name == other.name)

class SavedPlacesStore(context: Context) {
  private val prefs = context.getSharedPreferences("drivr_places", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(read())
  val state: StateFlow<SavedPlaces> = _state.asStateFlow()

  fun update(change: (SavedPlaces) -> SavedPlaces) {
    val next = change(_state.value)
    _state.value = next
    prefs.edit()
        .putString("home", next.home?.let { toJson(it).toString() })
        .putString("work", next.work?.let { toJson(it).toString() })
        .putString("recent", JSONArray(next.recent.map { toJson(it) }).toString())
        .apply()
  }

  private fun read(): SavedPlaces =
      runCatching {
            SavedPlaces(
                home = prefs.getString("home", null)?.let { fromJson(JSONObject(it)) },
                work = prefs.getString("work", null)?.let { fromJson(JSONObject(it)) },
                recent =
                    prefs.getString("recent", null)?.let { s ->
                      val a = JSONArray(s)
                      (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
                    }.orEmpty(),
            )
          }
          .getOrDefault(SavedPlaces())

  companion object {
    /** Only what is needed to route there and show it again; OSM tags are not kept. */
    fun toJson(p: Place): JSONObject =
        JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("lat", p.lat)
            .put("lon", p.lon)
            .put("address", p.address ?: JSONObject.NULL)
            .put("category", p.category?.name ?: JSONObject.NULL)

    fun fromJson(o: JSONObject): Place? =
        runCatching {
              Place(
                  id = o.getString("id"),
                  name = o.getString("name"),
                  category = o.optString("category").takeIf { it.isNotEmpty() && it != "null" }
                      ?.let { c -> PlaceCategory.entries.firstOrNull { it.name == c } },
                  lat = o.getDouble("lat"),
                  lon = o.getDouble("lon"),
                  address = o.optString("address").takeIf { it.isNotEmpty() && it != "null" },
              )
            }
            .getOrNull()
  }
}
