package com.aiope.gateway

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.servlet.http.*

class DataServlet : HttpServlet() {
    private val nasaKey: String = System.getenv("NASA_API_KEY") ?: "DEMO_KEY"
    private val geoapifyKey: String = System.getenv("GEOAPIFY_KEY") ?: ""
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, String>()
    private val G = GsonBuilder().disableHtmlEscaping().create()

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isApiAuthorized(req)) { resp.status = 401; resp.contentType = "application/json"; resp.writer.write("""{"error":{"message":"Unauthorized"}}"""); return }
        resp.contentType = "application/json; charset=utf-8"
        resp.setHeader("Access-Control-Allow-Origin", "*")

        val q = req.getParameter("q") ?: ""
        val lat = req.getParameter("lat") ?: ""
        val lon = req.getParameter("lon") ?: ""
        val extra = req.getParameter("extra") ?: ""

        if (q.isEmpty()) {
            resp.writer.write(G.toJson(mapOf("categories" to CATEGORIES.keys.sorted(), "usage" to "/v1/data?q=<category>&lat=<lat>&lon=<lon>")))
            return
        }

        // Geocode endpoint
        if (q == "geocode" || q == "places") {
            if (geoapifyKey.isEmpty()) {
                resp.status = 503; resp.writer.write("""{"error":{"message":"Geoapify key not configured on gateway"}}"""); return
            }
            val query = req.getParameter("query") ?: ""
            val geoUrl = if (q == "places")
                "https://api.geoapify.com/v2/places?categories=commercial,catering,service,entertainment,leisure,sport,tourism,accommodation,education,healthcare&conditions=named&filter=circle:$lon,$lat,5000&bias=proximity:$lon,$lat&limit=5&name=${java.net.URLEncoder.encode(query, "UTF-8")}&apiKey=$geoapifyKey"
            else
                "https://api.geoapify.com/v1/geocode/search?text=${java.net.URLEncoder.encode(query, "UTF-8")}&bias=proximity:$lon,$lat&limit=5&apiKey=$geoapifyKey"
            ServerLog.add("Geocode: $q -> $geoUrl")
            val request = Request.Builder().url(geoUrl).header("User-Agent", "AIOPE-Gateway/1.0").build()
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val result = G.toJson(mapOf("category" to q, "source" to "geoapify", "timestamp" to System.currentTimeMillis(),
                "data" to try { JsonParser.parseString(body) } catch (_: Exception) { JsonPrimitive(body.take(8000)) }))
            cache.put("$q|$query|$lat|$lon", result)
            resp.writer.write(result); return
        }

        try {
            val url = buildUrl(q, lat, lon, extra)
                ?: run { resp.status = 400; resp.writer.write("""{"error":{"message":"Unknown category: $q. Available: ${CATEGORIES.keys.sorted()}"}}"""); return }

            val cacheKey = "$q|$lat|$lon|$extra"
            val cached = cache.getIfPresent(cacheKey)
            if (cached != null) {
                ServerLog.add("Data cache hit: $q")
                resp.writer.write(cached)
                return
            }

            ServerLog.add("Data fetch: $q -> $url")
            val request = Request.Builder().url(url)
                .header("User-Agent", "AIOPE-Gateway/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                resp.status = 502
                resp.writer.write(G.toJson(mapOf("error" to mapOf("message" to "Upstream error: HTTP ${response.code}", "body" to body.take(500)), "source" to url)))
                return
            }

            val result = G.toJson(mapOf(
                "category" to q,
                "source" to CATEGORIES[q]?.second,
                "timestamp" to System.currentTimeMillis(),
                "data" to try { JsonParser.parseString(body) } catch (_: Exception) { JsonPrimitive(body.take(8000)) }
            ))

            cache.put(cacheKey, result)
            resp.writer.write(result)
        } catch (e: Exception) {
            resp.status = 500
            resp.writer.write(G.toJson(mapOf("error" to mapOf("message" to (e.message ?: "Internal error")))))
        }
    }

    private fun buildUrl(q: String, lat: String, lon: String, extra: String): String? {
        val entry = CATEGORIES[q] ?: return null
        return entry.first
            .replace("{lat}", lat.ifEmpty { "0" })
            .replace("{lon}", lon.ifEmpty { "0" })
            .replace("{extra}", extra)
            .replace("DEMO_KEY", nasaKey)
    }

    companion object {
        // category -> (url template, source name)
        val CATEGORIES = mapOf(
            // Weather
            "weather" to Pair(
                "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset&timezone=auto&forecast_days=3",
                "open-meteo"),
            "weather_hourly" to Pair(
                "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m&timezone=auto&forecast_hours=24",
                "open-meteo"),
            "alerts" to Pair(
                "https://api.weather.gov/alerts/active?point={lat},{lon}",
                "noaa-nws"),
            "air_quality" to Pair(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude={lat}&longitude={lon}&current=us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,ozone",
                "open-meteo"),
            "uv" to Pair(
                "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&daily=uv_index_max,uv_index_clear_sky_max&timezone=auto&forecast_days=3",
                "open-meteo"),
            "solar" to Pair(
                "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&hourly=shortwave_radiation,direct_radiation,diffuse_radiation,direct_normal_irradiance&timezone=auto&forecast_hours=24",
                "open-meteo"),

            // Space
            "iss" to Pair(
                "http://api.open-notify.org/iss-now.json",
                "open-notify"),
            "astronauts" to Pair(
                "http://api.open-notify.org/astros.json",
                "open-notify"),
            "apod" to Pair(
                "https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY",
                "nasa"),
            "asteroids" to Pair(
                "https://api.nasa.gov/neo/rest/v1/feed/today?api_key=DEMO_KEY",
                "nasa-neows"),
            "solar_flares" to Pair(
                "https://api.nasa.gov/DONKI/FLR?api_key=DEMO_KEY",
                "nasa-donki"),
            "cme" to Pair(
                "https://api.nasa.gov/DONKI/CME?api_key=DEMO_KEY",
                "nasa-donki"),
            "geomagnetic" to Pair(
                "https://api.nasa.gov/DONKI/GST?api_key=DEMO_KEY",
                "nasa-donki"),
            "earth_events" to Pair(
                "https://eonet.gsfc.nasa.gov/api/v3/events?limit=20",
                "nasa-eonet"),
            "mars_photos" to Pair(
                "https://api.nasa.gov/mars-photos/api/v1/rovers/curiosity/latest_photos?page=1&api_key=DEMO_KEY",
                "nasa-mars"),
            "sunrise_sunset" to Pair(
                "https://api.sunrise-sunset.org/json?lat={lat}&lng={lon}&formatted=0",
                "sunrise-sunset.org"),

            // Earthquakes & Geology
            "earthquakes" to Pair(
                "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson",
                "usgs"),
            "earthquakes_significant" to Pair(
                "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_month.geojson",
                "usgs"),

            // Ocean & Marine
            "tides" to Pair(
                "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?date=latest&station={extra}&product=water_level&datum=MLLW&units=english&time_zone=lst_ldt&format=json",
                "noaa-tides"),
            "ocean_temp" to Pair(
                "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?date=latest&station={extra}&product=water_temperature&units=english&format=json",
                "noaa-tides"),

            // Aviation
            "flights" to Pair(
                "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m&models=best_match",
                "open-meteo-placeholder"),

            // Fire & Hazards
            "fires" to Pair(
                "https://eonet.gsfc.nasa.gov/api/v3/events?category=wildfires&limit=20",
                "nasa-eonet-fires"),
            "impact_risk" to Pair(
                "https://ssd-api.jpl.nasa.gov/sentry.api",
                "nasa-jpl"),

            // Geolocation & Time
            "ip_location" to Pair(
                "http://ip-api.com/json/",
                "ip-api"),
            "time" to Pair(
                "https://timeapi.io/api/time/current/coordinate?latitude={lat}&longitude={lon}",
                "timeapi.io")
        )
    }
}
