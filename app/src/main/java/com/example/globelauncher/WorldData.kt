package com.example.globelauncher

import android.util.Base64
import java.util.zip.Inflater

/**
 * Rough world map on a 2 degree grid (hand-built approximation, not survey-grade borders).
 * Each cell stores a country index; 0 means sea.
 */
object WorldData {
    const val COLS = 180
    const val ROWS = 90

    private val RASTER = listOf(
        "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",
        "ES0="
    ).joinToString("")

    private val TABLE = listOf(
        "United States|LON;Canada|LON;Mexico|America/Mexico_City;Guatemala|America/Guatemala;Belize|Ameri",
        "ca/Belize;Honduras|America/Tegucigalpa;El Salvador|America/El_Salvador;Nicaragua|America/Managua",
        ";Costa Rica|America/Costa_Rica;Panama|America/Panama;Cuba|America/Havana;Haiti|America/Port-au-P",
        "rince;Dominican Republic|America/Santo_Domingo;Greenland|America/Nuuk;Iceland|Atlantic/Reykjavik",
        ";Colombia|America/Bogota;Venezuela|America/Caracas;Guyana|America/Guyana;Suriname|America/Parama",
        "ribo;Brazil|America/Sao_Paulo;Ecuador|America/Guayaquil;Peru|America/Lima;Bolivia|America/La_Paz",
        ";Paraguay|America/Asuncion;Uruguay|America/Montevideo;Argentina|America/Argentina/Buenos_Aires;C",
        "hile|America/Santiago;Portugal|Europe/Lisbon;Spain|Europe/Madrid;France|Europe/Paris;Ireland|Eur",
        "ope/Dublin;United Kingdom|Europe/London;Belgium|Europe/Brussels;Netherlands|Europe/Amsterdam;Ger",
        "many|Europe/Berlin;Switzerland|Europe/Zurich;Austria|Europe/Vienna;Italy|Europe/Rome;Denmark|Eur",
        "ope/Copenhagen;Norway|Europe/Oslo;Sweden|Europe/Stockholm;Finland|Europe/Helsinki;Estonia|Europe",
        "/Tallinn;Latvia|Europe/Riga;Lithuania|Europe/Vilnius;Poland|Europe/Warsaw;Czechia|Europe/Prague;",
        "Slovakia|Europe/Bratislava;Hungary|Europe/Budapest;Romania|Europe/Bucharest;Bulgaria|Europe/Sofi",
        "a;Greece|Europe/Athens;Serbia|Europe/Belgrade;Croatia|Europe/Zagreb;Bosnia|Europe/Sarajevo;Alban",
        "ia|Europe/Tirane;Belarus|Europe/Minsk;Ukraine|Europe/Kyiv;Russia|LON;Turkey|Europe/Istanbul;Geor",
        "gia|Asia/Tbilisi;Armenia|Asia/Yerevan;Azerbaijan|Asia/Baku;Iran|Asia/Tehran;Iraq|Asia/Baghdad;Sy",
        "ria|Asia/Damascus;Jordan|Asia/Amman;Israel|Asia/Jerusalem;Saudi Arabia|Asia/Riyadh;Kuwait|Asia/K",
        "uwait;United Arab Emirates|Asia/Dubai;Oman|Asia/Muscat;Yemen|Asia/Aden;Egypt|Africa/Cairo;Afghan",
        "istan|Asia/Kabul;Pakistan|Asia/Karachi;India|Asia/Kolkata;Nepal|Asia/Kathmandu;Bangladesh|Asia/D",
        "haka;Sri Lanka|Asia/Colombo;Myanmar|Asia/Yangon;Thailand|Asia/Bangkok;Laos|Asia/Vientiane;Vietna",
        "m|Asia/Ho_Chi_Minh;Cambodia|Asia/Phnom_Penh;Malaysia|Asia/Kuala_Lumpur;Indonesia|Asia/Jakarta;Ph",
        "ilippines|Asia/Manila;Papua New Guinea|Pacific/Port_Moresby;China|Asia/Shanghai;Mongolia|Asia/Ul",
        "aanbaatar;Taiwan|Asia/Taipei;North Korea|Asia/Pyongyang;South Korea|Asia/Seoul;Japan|Asia/Tokyo;",
        "Kazakhstan|Asia/Almaty;Uzbekistan|Asia/Tashkent;Turkmenistan|Asia/Ashgabat;Kyrgyzstan|Asia/Bishk",
        "ek;Tajikistan|Asia/Dushanbe;Morocco|Africa/Casablanca;Algeria|Africa/Algiers;Tunisia|Africa/Tuni",
        "s;Libya|Africa/Tripoli;Sudan|Africa/Khartoum;South Sudan|Africa/Juba;Ethiopia|Africa/Addis_Ababa",
        ";Eritrea|Africa/Asmara;Somalia|Africa/Mogadishu;Kenya|Africa/Nairobi;Uganda|Africa/Kampala;Tanza",
        "nia|Africa/Dar_es_Salaam;DR Congo|Africa/Kinshasa;Congo|Africa/Brazzaville;Gabon|Africa/Librevil",
        "le;Cameroon|Africa/Douala;Nigeria|Africa/Lagos;Niger|Africa/Niamey;Chad|Africa/Ndjamena;Central ",
        "African Rep.|Africa/Bangui;Mali|Africa/Bamako;Mauritania|Africa/Nouakchott;Senegal|Africa/Dakar;",
        "Guinea|Africa/Conakry;Sierra Leone|Africa/Freetown;Liberia|Africa/Monrovia;Cote d'Ivoire|Africa/",
        "Abidjan;Ghana|Africa/Accra;Burkina Faso|Africa/Ouagadougou;Togo|Africa/Lome;Benin|Africa/Porto-N",
        "ovo;Angola|Africa/Luanda;Zambia|Africa/Lusaka;Zimbabwe|Africa/Harare;Mozambique|Africa/Maputo;Ma",
        "lawi|Africa/Blantyre;Namibia|Africa/Windhoek;Botswana|Africa/Gaborone;South Africa|Africa/Johann",
        "esburg;Madagascar|Indian/Antananarivo;Australia|LON;New Zealand|Pacific/Auckland;Antarctica|LON"
    ).joinToString("")

    val names: Array<String>
    val zones: Array<String>
    private val grid = ByteArray(ROWS * COLS)

    init {
        val data = Base64.decode(RASTER, Base64.DEFAULT)
        val inflater = Inflater()
        inflater.setInput(data)
        var off = 0
        while (off < grid.size && !inflater.finished()) {
            val got = inflater.inflate(grid, off, grid.size - off)
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            off += got
        }
        inflater.end()

        val items = TABLE.split(";")
        val nameList = ArrayList<String>()
        val zoneList = ArrayList<String>()
        nameList.add("")
        zoneList.add("LON")
        for (item in items) {
            val parts = item.split("|")
            nameList.add(parts[0])
            zoneList.add(if (parts.size > 1) parts[1] else "LON")
        }
        names = nameList.toTypedArray()
        zones = zoneList.toTypedArray()
    }

    fun idAtCell(row: Int, col: Int): Int = grid[row * COLS + col].toInt() and 0xFF

    fun countryAt(latDeg: Double, lonDeg: Double): Int {
        val row = ((90.0 - latDeg) / 2.0).toInt().coerceIn(0, ROWS - 1)
        val col = ((lonDeg + 180.0) / 2.0).toInt().coerceIn(0, COLS - 1)
        val id = idAtCell(row, col)
        return if (id < names.size) id else 0
    }
}
