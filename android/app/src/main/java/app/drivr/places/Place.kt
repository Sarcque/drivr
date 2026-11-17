package app.drivr.places

import app.drivr.i18n.L10n
import app.drivr.R

/**
 * What a driver looks for. Each category maps to OpenStreetMap tags, which is all the data there
 * is: no Google Places, no paid POI database, no keys.
 */
enum class PlaceCategory(@androidx.annotation.StringRes val labelRes: Int, val osmFilters: List<String>) {
  FUEL(R.string.category_fuel, listOf("[amenity=fuel]")),
  CHARGING(R.string.category_charging, listOf("[amenity=charging_station]")),
  FOOD(R.string.category_food, listOf("[amenity~\"^(restaurant|fast_food)$\"]")),
  COFFEE(R.string.category_coffee, listOf("[amenity=cafe]")),
  GROCERIES(R.string.category_groceries, listOf("[shop~\"^(supermarket|convenience)$\"]")),
  SHOPS(R.string.category_shops, listOf("[shop~\"^(mall|department_store|bakery|chemist|doityourself|hardware|electronics)$\"]")),
  PARKING(R.string.category_parking, listOf("[amenity=parking][access!~\"^(private|no|customers)$\"]")),
  REST(R.string.category_rest, listOf("[highway~\"^(services|rest_area)$\"]", "[amenity=toilets][access!=private]"));

  val label: String get() = L10n.s(labelRes)

  companion object {
    fun fromTags(tags: Map<String, String>): PlaceCategory? {
      val amenity = tags["amenity"]
      val shop = tags["shop"]
      return when {
        amenity == "fuel" -> FUEL
        amenity == "charging_station" -> CHARGING
        amenity == "restaurant" || amenity == "fast_food" -> FOOD
        amenity == "cafe" -> COFFEE
        shop == "supermarket" || shop == "convenience" -> GROCERIES
        amenity == "parking" -> PARKING
        tags["highway"] == "services" || tags["highway"] == "rest_area" || amenity == "toilets" -> REST
        shop != null -> SHOPS
        else -> null
      }
    }
  }
}

/** A place from OSM (Overpass) or from free-text search (Photon). */
data class Place(
    /** "node/123", "way/456": the OSM object, so results from both sources de-duplicate. */
    val id: String,
    val name: String,
    val category: PlaceCategory?,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String> = emptyMap(),
    /** One-line address or locality, when known. */
    val address: String? = null,
) {
  val brand: String? get() = tags["brand"] ?: tags["operator"]

  /** "Diesel", "Euro 95 (E10)", "LPG" from OSM fuel:* tags. */
  val fuels: List<String>
    get() = FUEL_LABELS.mapNotNull { (key, label) -> translated(label).takeIf { tags["fuel:$key"] == "yes" } }

  /** Charging connectors and the highest power: "CCS 150 kW", "Type 2 22 kW". */
  val sockets: List<String>
    get() =
        SOCKET_LABELS.mapNotNull { (key, label) ->
          val count = tags["socket:$key"] ?: return@mapNotNull null
          if (count == "no" || count == "0") return@mapNotNull null
          val kw = parsePowerKw(tags["socket:$key:output"])
          if (kw != null) "${translated(label)} ${formatKw(kw)}" else translated(label)
        }

  /** Product names (Diesel, CCS) stay as they are; the few plain words are translated. */
  private fun translated(label: String): String =
      when (label) {
        "Hydrogen" -> L10n.s(R.string.fuel_hydrogen)
        "Truck diesel" -> L10n.s(R.string.fuel_truck_diesel)
        "Type 2 cable" -> L10n.s(R.string.socket_type2_cable)
        else -> label
      }

  val maxPowerKw: Double?
    get() =
        (SOCKET_LABELS.mapNotNull { parsePowerKw(tags["socket:${it.first}:output"]) } +
                listOfNotNull(parsePowerKw(tags["charging_station:output"])))
            .maxOrNull()

  val openingHours: String? get() = tags["opening_hours"]

  val is24h: Boolean get() = openingHours?.trim() == "24/7"

  /** The OSM "charge" tag ("€0.59/kWh"). Volunteer-entered, may be outdated. */
  val osmCharge: String? get() = tags["charge"]

  val cuisine: String?
    get() = tags["cuisine"]?.split(';')?.firstOrNull()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

  val hasToilets: Boolean get() = tags["toilets"] == "yes"
  val hasShop: Boolean get() = tags["shop"] == "convenience" || tags["shop"] == "kiosk"
  val selfService: Boolean get() = tags["self_service"] == "yes" || tags["automated"] == "yes"

  companion object {
    val FUEL_LABELS =
        listOf(
            "diesel" to "Diesel",
            "octane_95" to "Euro 95",
            "e10" to "E10",
            "octane_98" to "Super 98",
            "lpg" to "LPG",
            "cng" to "CNG",
            "adblue" to "AdBlue",
            "hydrogen" to "Hydrogen",
            "HGV_diesel" to "Truck diesel",
        )

    val SOCKET_LABELS =
        listOf(
            "type2_combo" to "CCS",
            "chademo" to "CHAdeMO",
            "type2" to "Type 2",
            "type2_cable" to "Type 2 cable",
            "tesla_supercharger" to "Tesla",
            "schuko" to "Schuko",
        )

    /** "150 kW", "22kW", "11000 W" → kW. */
    fun parsePowerKw(raw: String?): Double? {
      if (raw.isNullOrBlank()) return null
      val m = Regex("""([\d.,]+)\s*(kW|W|kVA)?""", RegexOption.IGNORE_CASE).find(raw) ?: return null
      val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
      return when (m.groupValues[2].lowercase()) {
        "w" -> n / 1000.0
        else -> n
      }
    }

    fun formatKw(kw: Double): String = if (kw % 1.0 == 0.0) "${kw.toInt()} kW" else L10n.format("%.1f kW", kw)
  }
}
