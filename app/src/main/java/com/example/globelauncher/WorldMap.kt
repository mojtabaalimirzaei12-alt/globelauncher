package com.example.globelauncher

import kotlin.math.cos
import kotlin.math.PI

/** Very small JSON reader (enough for the TopoJSON world map file). */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        return p.value()
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] <= ' ') i++
        }

        fun value(): Any? {
            ws()
            val c = s[i]
            return when (c) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> {
                    i += 4
                    true
                }
                'f' -> {
                    i += 5
                    false
                }
                'n' -> {
                    i += 4
                    null
                }
                else -> num()
            }
        }

        fun obj(): HashMap<String, Any?> {
            val m = HashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                i++ // the colon
                m[k] = value()
                ws()
                if (s[i] == ',') {
                    i++
                } else {
                    i++ // the closing brace
                    break
                }
            }
            return m
        }

        fun arr(): ArrayList<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') {
                i++
                return a
            }
            while (true) {
                a.add(value())
                ws()
                if (s[i] == ',') {
                    i++
                } else {
                    i++ // the closing bracket
                    break
                }
            }
            return a
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') break
                if (c == '\\') {
                    val e = s[i++]
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        fun num(): Double {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c in '0'..'9')) i++ else break
            }
            return s.substring(start, i).toDouble()
        }
    }
}

class Ring(val lon: FloatArray, val lat: FloatArray) {
    var minLon = 1000f
    var maxLon = -1000f
    var minLat = 1000f
    var maxLat = -1000f

    init {
        for (k in lon.indices) {
            if (lon[k] < minLon) minLon = lon[k]
            if (lon[k] > maxLon) maxLon = lon[k]
            if (lat[k] < minLat) minLat = lat[k]
            if (lat[k] > maxLat) maxLat = lat[k]
        }
    }

    /** Even-odd point in polygon test (degrees). */
    fun contains(px: Double, py: Double): Boolean {
        if (px < minLon || px > maxLon || py < minLat || py > maxLat) return false
        var inside = false
        var j = lon.size - 1
        for (k in lon.indices) {
            val xi = lon[k].toDouble()
            val yi = lat[k].toDouble()
            val xj = lon[j].toDouble()
            val yj = lat[j].toDouble()
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = k
        }
        return inside
    }
}

class Country(val name: String, val zone: String, val rings: List<Ring>) {
    fun contains(lonDeg: Double, latDeg: Double): Boolean {
        var inside = false
        for (r in rings) {
            if (r.contains(lonDeg, latDeg)) inside = !inside
        }
        return inside
    }
}

class World(
    val countries: List<Country>,
    /** All border lines (each arc once), already thinned out, in degrees. */
    val drawLon: FloatArray,
    val drawLat: FloatArray,
    /** drawLon/drawLat index where each arc starts, plus one extra entry at the end. */
    val arcStart: IntArray
) {
    val iran: Int = countries.indexOfFirst { it.name == "Iran" }
    val antarctica: Int = countries.indexOfFirst { it.name == "Antarctica" }

    fun countryAt(latDeg: Double, lonDeg: Double): Int {
        if (latDeg < -62.0 && antarctica >= 0) return antarctica
        for (i in countries.indices) {
            if (countries[i].contains(lonDeg, latDeg)) return i
        }
        return -1
    }
}

object TopoWorld {
    private const val THINNING_DEG = 0.30f

    fun parse(text: String): World {
        val root = MiniJson.parse(text) as Map<*, *>
        val tr = root["transform"] as Map<*, *>?
        var sx = 1.0
        var sy = 1.0
        var tx = 0.0
        var ty = 0.0
        if (tr != null) {
            val sc = tr["scale"] as List<*>
            val t = tr["translate"] as List<*>
            sx = sc[0] as Double
            sy = sc[1] as Double
            tx = t[0] as Double
            ty = t[1] as Double
        }

        // Decode arcs
        val arcsJson = root["arcs"] as List<*>
        val n = arcsJson.size
        val aLon = Array(n) { FloatArray(0) }
        val aLat = Array(n) { FloatArray(0) }
        for (a in 0 until n) {
            val pts = arcsJson[a] as List<*>
            val m = pts.size
            val lo = FloatArray(m)
            val la = FloatArray(m)
            var x = 0.0
            var y = 0.0
            for (k in 0 until m) {
                val p = pts[k] as List<*>
                val px = p[0] as Double
                val py = p[1] as Double
                if (tr != null) {
                    x += px
                    y += py
                    lo[k] = (x * sx + tx).toFloat()
                    la[k] = (y * sy + ty).toFloat()
                } else {
                    lo[k] = px.toFloat()
                    la[k] = py.toFloat()
                }
            }
            aLon[a] = lo
            aLat[a] = la
        }

        // Countries
        val objects = root["objects"] as Map<*, *>
        val coll = (objects["countries"] ?: objects.values.first()) as Map<*, *>
        val geoms = coll["geometries"] as List<*>
        val countries = ArrayList<Country>()
        for (g in geoms) {
            val gm = g as Map<*, *>
            val props = gm["properties"] as? Map<*, *>
            val name = (props?.get("name") as? String) ?: "Unknown"
            val type = gm["type"] as? String ?: continue
            val rings = ArrayList<Ring>()
            val arcs = gm["arcs"] as? List<*> ?: continue
            if (type == "Polygon") {
                for (r in arcs) rings.add(buildRing(r as List<*>, aLon, aLat))
            } else if (type == "MultiPolygon") {
                for (poly in arcs) {
                    for (r in poly as List<*>) rings.add(buildRing(r as List<*>, aLon, aLat))
                }
            }
            countries.add(Country(name, Zones.zoneFor(name), rings))
        }

        // Thin out the arcs for drawing
        val starts = IntArray(n + 1)
        var total = 0
        val keepLists = ArrayList<IntArray>(n)
        for (a in 0 until n) {
            val keep = thin(aLon[a], aLat[a])
            keepLists.add(keep)
            starts[a] = total
            total += keep.size
        }
        starts[n] = total
        val dLon = FloatArray(total)
        val dLat = FloatArray(total)
        for (a in 0 until n) {
            val keep = keepLists[a]
            var o = starts[a]
            for (k in keep) {
                dLon[o] = aLon[a][k]
                dLat[o] = aLat[a][k]
                o++
            }
        }
        return World(countries, dLon, dLat, starts)
    }

    private fun thin(lon: FloatArray, lat: FloatArray): IntArray {
        val m = lon.size
        if (m <= 2) return IntArray(m) { it }
        val out = IntArray(m)
        var c = 0
        out[c++] = 0
        var last = 0
        val thr2 = THINNING_DEG * THINNING_DEG
        for (k in 1 until m - 1) {
            val dx = (lon[k] - lon[last]) * cos(lat[k] * PI / 180.0).toFloat()
            val dy = lat[k] - lat[last]
            if (dx * dx + dy * dy >= thr2) {
                out[c++] = k
                last = k
            }
        }
        out[c++] = m - 1
        return out.copyOf(c)
    }

    private fun buildRing(idx: List<*>, aLon: Array<FloatArray>, aLat: Array<FloatArray>): Ring {
        var total = 0
        for (v in idx) {
            val a = (v as Double).toInt()
            val ai = if (a < 0) -a - 1 else a
            total += aLon[ai].size
        }
        total -= (idx.size - 1)
        if (total < 1) total = 1
        val lo = FloatArray(total)
        val la = FloatArray(total)
        var o = 0
        var first = true
        for (v in idx) {
            val a = (v as Double).toInt()
            val rev = a < 0
            val ai = if (rev) -a - 1 else a
            val sl = aLon[ai]
            val sa = aLat[ai]
            val m = sl.size
            if (rev) {
                var k = m - 1
                while (k >= 0) {
                    if (first || k != m - 1) {
                        if (o < total) {
                            lo[o] = sl[k]
                            la[o] = sa[k]
                            o++
                        }
                    }
                    k--
                }
            } else {
                for (k in 0 until m) {
                    if (first || k != 0) {
                        if (o < total) {
                            lo[o] = sl[k]
                            la[o] = sa[k]
                            o++
                        }
                    }
                }
            }
            first = false
        }
        return Ring(lo, la)
    }
}

object Zones {
    private val table: Map<String, String> = mapOf(
        "Fiji" to "Pacific/Fiji", "Tanzania" to "Africa/Dar_es_Salaam", "W. Sahara" to "Africa/El_Aaiun",
        "Canada" to "LON", "United States of America" to "LON", "Kazakhstan" to "Asia/Almaty",
        "Uzbekistan" to "Asia/Tashkent", "Papua New Guinea" to "Pacific/Port_Moresby",
        "Indonesia" to "Asia/Jakarta", "Argentina" to "America/Argentina/Buenos_Aires",
        "Chile" to "America/Santiago", "Dem. Rep. Congo" to "Africa/Kinshasa", "Somalia" to "Africa/Mogadishu",
        "Kenya" to "Africa/Nairobi", "Sudan" to "Africa/Khartoum", "Chad" to "Africa/Ndjamena",
        "Haiti" to "America/Port-au-Prince", "Dominican Rep." to "America/Santo_Domingo", "Russia" to "LON",
        "Bahamas" to "America/Nassau", "Falkland Is." to "Atlantic/Stanley", "Norway" to "Europe/Oslo",
        "Greenland" to "America/Godthab", "Fr. S. Antarctic Lands" to "Indian/Kerguelen",
        "Timor-Leste" to "Asia/Dili", "South Africa" to "Africa/Johannesburg", "Lesotho" to "Africa/Maseru",
        "Mexico" to "America/Mexico_City", "Uruguay" to "America/Montevideo", "Brazil" to "America/Sao_Paulo",
        "Bolivia" to "America/La_Paz", "Peru" to "America/Lima", "Colombia" to "America/Bogota",
        "Panama" to "America/Panama", "Costa Rica" to "America/Costa_Rica", "Nicaragua" to "America/Managua",
        "Honduras" to "America/Tegucigalpa", "El Salvador" to "America/El_Salvador",
        "Guatemala" to "America/Guatemala", "Belize" to "America/Belize", "Venezuela" to "America/Caracas",
        "Guyana" to "America/Guyana", "Suriname" to "America/Paramaribo", "France" to "Europe/Paris",
        "Ecuador" to "America/Guayaquil", "Puerto Rico" to "America/Puerto_Rico", "Jamaica" to "America/Jamaica",
        "Cuba" to "America/Havana", "Zimbabwe" to "Africa/Harare", "Botswana" to "Africa/Gaborone",
        "Namibia" to "Africa/Windhoek", "Senegal" to "Africa/Dakar", "Mali" to "Africa/Bamako",
        "Mauritania" to "Africa/Nouakchott", "Benin" to "Africa/Porto-Novo", "Niger" to "Africa/Niamey",
        "Nigeria" to "Africa/Lagos", "Cameroon" to "Africa/Douala", "Togo" to "Africa/Lome",
        "Ghana" to "Africa/Accra", "Côte d'Ivoire" to "Africa/Abidjan", "Guinea" to "Africa/Conakry",
        "Guinea-Bissau" to "Africa/Bissau", "Liberia" to "Africa/Monrovia", "Sierra Leone" to "Africa/Freetown",
        "Burkina Faso" to "Africa/Ouagadougou", "Central African Rep." to "Africa/Bangui",
        "Congo" to "Africa/Brazzaville", "Gabon" to "Africa/Libreville", "Eq. Guinea" to "Africa/Malabo",
        "Zambia" to "Africa/Lusaka", "Malawi" to "Africa/Blantyre", "Mozambique" to "Africa/Maputo",
        "eSwatini" to "Africa/Mbabane", "Angola" to "Africa/Luanda", "Burundi" to "Africa/Bujumbura",
        "Israel" to "Asia/Jerusalem", "Lebanon" to "Asia/Beirut", "Madagascar" to "Indian/Antananarivo",
        "Palestine" to "Asia/Gaza", "Gambia" to "Africa/Banjul", "Tunisia" to "Africa/Tunis",
        "Algeria" to "Africa/Algiers", "Jordan" to "Asia/Amman", "United Arab Emirates" to "Asia/Dubai",
        "Qatar" to "Asia/Qatar", "Kuwait" to "Asia/Kuwait", "Iraq" to "Asia/Baghdad", "Oman" to "Asia/Muscat",
        "Vanuatu" to "Pacific/Efate", "Cambodia" to "Asia/Phnom_Penh", "Thailand" to "Asia/Bangkok",
        "Laos" to "Asia/Vientiane", "Myanmar" to "Asia/Yangon", "Vietnam" to "Asia/Ho_Chi_Minh",
        "North Korea" to "Asia/Pyongyang", "South Korea" to "Asia/Seoul", "Mongolia" to "Asia/Ulaanbaatar",
        "India" to "Asia/Kolkata", "Bangladesh" to "Asia/Dhaka", "Bhutan" to "Asia/Thimphu",
        "Nepal" to "Asia/Kathmandu", "Pakistan" to "Asia/Karachi", "Afghanistan" to "Asia/Kabul",
        "Tajikistan" to "Asia/Dushanbe", "Kyrgyzstan" to "Asia/Bishkek", "Turkmenistan" to "Asia/Ashgabat",
        "Iran" to "GMT+03:30", "Syria" to "Asia/Damascus", "Armenia" to "Asia/Yerevan",
        "Sweden" to "Europe/Stockholm", "Belarus" to "Europe/Minsk", "Ukraine" to "Europe/Kiev",
        "Poland" to "Europe/Warsaw", "Austria" to "Europe/Vienna", "Hungary" to "Europe/Budapest",
        "Moldova" to "Europe/Chisinau", "Romania" to "Europe/Bucharest", "Lithuania" to "Europe/Vilnius",
        "Latvia" to "Europe/Riga", "Estonia" to "Europe/Tallinn", "Germany" to "Europe/Berlin",
        "Bulgaria" to "Europe/Sofia", "Greece" to "Europe/Athens", "Turkey" to "Europe/Istanbul",
        "Albania" to "Europe/Tirane", "Croatia" to "Europe/Zagreb", "Switzerland" to "Europe/Zurich",
        "Luxembourg" to "Europe/Luxembourg", "Belgium" to "Europe/Brussels", "Netherlands" to "Europe/Amsterdam",
        "Portugal" to "Europe/Lisbon", "Spain" to "Europe/Madrid", "Ireland" to "Europe/Dublin",
        "New Caledonia" to "Pacific/Noumea", "Solomon Is." to "Pacific/Guadalcanal",
        "New Zealand" to "Pacific/Auckland", "Australia" to "LON", "Sri Lanka" to "Asia/Colombo",
        "China" to "Asia/Shanghai", "Taiwan" to "Asia/Taipei", "Italy" to "Europe/Rome",
        "Denmark" to "Europe/Copenhagen", "United Kingdom" to "Europe/London", "Iceland" to "Atlantic/Reykjavik",
        "Azerbaijan" to "Asia/Baku", "Georgia" to "Asia/Tbilisi", "Philippines" to "Asia/Manila",
        "Malaysia" to "Asia/Kuala_Lumpur", "Brunei" to "Asia/Brunei", "Slovenia" to "Europe/Ljubljana",
        "Finland" to "Europe/Helsinki", "Slovakia" to "Europe/Bratislava", "Czechia" to "Europe/Prague",
        "Eritrea" to "Africa/Asmara", "Japan" to "Asia/Tokyo", "Paraguay" to "America/Asuncion",
        "Yemen" to "Asia/Aden", "Saudi Arabia" to "Asia/Riyadh", "Antarctica" to "LON",
        "N. Cyprus" to "Asia/Nicosia", "Cyprus" to "Asia/Nicosia", "Morocco" to "Africa/Casablanca",
        "Egypt" to "Africa/Cairo", "Libya" to "Africa/Tripoli", "Ethiopia" to "Africa/Addis_Ababa",
        "Djibouti" to "Africa/Djibouti", "Somaliland" to "Africa/Mogadishu", "Uganda" to "Africa/Kampala",
        "Rwanda" to "Africa/Kigali", "Bosnia and Herz." to "Europe/Sarajevo",
        "North Macedonia" to "Europe/Skopje", "Macedonia" to "Europe/Skopje", "Serbia" to "Europe/Belgrade",
        "Montenegro" to "Europe/Podgorica", "Kosovo" to "Europe/Belgrade",
        "Trinidad and Tobago" to "America/Port_of_Spain", "S. Sudan" to "Africa/Juba"
    )

    /** Time zone id for a country name, or "LON" (use the longitude) when unknown. */
    fun zoneFor(name: String): String = table[name] ?: "LON"

    /** Longer, friendlier country names for the screen. */
    fun pretty(name: String): String {
        var s = name
        if (s == "Fr. S. Antarctic Lands") return "French Southern Lands"
        s = s.replace("Dem. Rep.", "Democratic Republic of the")
            .replace("Rep.", "Republic")
            .replace("Herz.", "Herzegovina")
            .replace("Is.", "Islands")
            .replace("Eq.", "Equatorial")
        if (s.startsWith("S. ")) s = "South " + s.substring(3)
        if (s.startsWith("N. ")) s = "Northern " + s.substring(3)
        if (s.startsWith("W. ")) s = "Western " + s.substring(3)
        return s
    }
}
