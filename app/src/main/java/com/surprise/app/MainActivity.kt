package com.surprise.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

// =====================================================================
//  Données
// =====================================================================
class Goal(val lat: Double, val lon: Double, val secondsLeft: Long)
class Reveal(val hint: String, val message: String, val photos: List<String>)

val durations = listOf(
    "30 min" to 30, "1 h" to 60, "3 h" to 180,
    "12 h" to 720, "24 h" to 1440, "7 jours" to 10080
)

fun durationLabel(min: Int) = durations.firstOrNull { it.second == min }?.first ?: "$min min"

fun fmt(sec: Long): String {
    val d = sec / 86400
    val h = sec % 86400 / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return when {
        d > 0 -> "$d j ${h} h ${m} min"
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%02d:%02d".format(m, s)
    }
}

object Repo {
    private val BASE = BuildConfig.SUPABASE_URL.trimEnd('/')

    /** Appelle une fonction SQL Supabase (REST /rpc). */
    private suspend fun rpc(fn: String, body: JSONObject): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = URL("$BASE/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 30_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                if (BuildConfig.SUPABASE_KEY.startsWith("eyJ"))
                    c.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val http = c.responseCode
                val text = (if (http in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                http to text
            } finally {
                c.disconnect()
            }
        }

    /** Enregistre la surprise (avec sa durée en minutes) et renvoie le code à 14 chiffres. */
    suspend fun save(
        lat: Double, lon: Double, hint: String, msg: String,
        photos: List<String>, minutes: Int
    ): String {
        val rnd = SecureRandom()
        repeat(5) {
            val code = (1..14).joinToString("") { rnd.nextInt(10).toString() }
            val body = JSONObject().put("p_code", code).put("p_lat", lat).put("p_lon", lon)
                .put("p_hint", hint).put("p_msg", msg).put("p_photos", JSONArray(photos))
                .put("p_minutes", minutes)
            val (http, _) = rpc("create_surprise", body)
            if (http in 200..299) return code
            if (http != 409) throw IllegalStateException("HTTP $http") // 409 = code déjà pris
        }
        throw IllegalStateException("Impossible de générer un code")
    }

    /** Vérifie le code. Statuts : ok, notfound, found (déjà trouvée), expired. */
    suspend fun check(code: String): Pair<String, Goal?> {
        val (http, text) = rpc("check_surprise", JSONObject().put("p_code", code))
        if (http !in 200..299) throw IllegalStateException("HTTP $http")
        val rows = JSONArray(text)
        if (rows.length() == 0) return "notfound" to null
        val j = rows.getJSONObject(0)
        val st = j.getString("status")
        if (st != "ok") return st to null
        return st to Goal(j.getDouble("lat"), j.getDouble("lon"), j.getLong("seconds_left"))
    }

    /** À l'arrivée : le serveur vérifie la position, donne le contenu et fait expirer le code. */
    suspend fun claim(code: String, lat: Double, lon: Double): Pair<String, Reveal?> {
        val body = JSONObject().put("p_code", code).put("p_lat", lat).put("p_lon", lon)
        val (http, text) = rpc("claim_surprise", body)
        if (http !in 200..299) throw IllegalStateException("HTTP $http")
        val rows = JSONArray(text)
        if (rows.length() == 0) return "notfound" to null
        val j = rows.getJSONObject(0)
        val st = j.getString("status")
        if (st != "ok") return st to null
        val arr = j.getJSONArray("photos")
        return st to Reveal(j.optString("hint"), j.optString("msg"), List(arr.length()) { arr.getString(it) })
    }

    /** Itinéraire (OSM). Si tout échoue : ligne droite. */
    suspend fun route(a: GeoPoint, b: GeoPoint): List<GeoPoint> = withContext(Dispatchers.IO) {
        val coords = "${a.longitude},${a.latitude};${b.longitude},${b.latitude}"
        val urls = listOf(
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords?overview=full&geometries=geojson",
            "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson"
        )
        for (u in urls) {
            val r = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "SurpriseApp/1.0")
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                val pts = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .getJSONArray("routes").getJSONObject(0)
                    .getJSONObject("geometry").getJSONArray("coordinates")
                List(pts.length()) { GeoPoint(pts.getJSONArray(it).getDouble(1), pts.getJSONArray(it).getDouble(0)) }
            }.getOrNull()
            if (r != null && r.size >= 2) return@withContext r
        }
        listOf(a, b)
    }
}

// =====================================================================
//  Utilitaires
// =====================================================================
fun loadPhoto(path: String): Bitmap {
    val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
    val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
    }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}

fun Bitmap.toB64(): String {
    val k = 640f / maxOf(width, height)
    val s = Bitmap.createScaledBitmap(this, (width * k).toInt(), (height * k).toInt(), true)
    val o = ByteArrayOutputStream()
    s.compress(Bitmap.CompressFormat.JPEG, 55, o)
    return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
}

/** Renvoie seulement les `meters` mètres du chemin qui se trouvent devant l'utilisateur. */
fun lineAhead(path: List<GeoPoint>, me: GeoPoint, meters: Double = 20.0): List<GeoPoint> {
    if (path.size < 2) return emptyList()
    val kLat = 111_320.0
    val kLon = kLat * Math.cos(Math.toRadians(me.latitude))
    fun x(p: GeoPoint) = (p.longitude - me.longitude) * kLon
    fun y(p: GeoPoint) = (p.latitude - me.latitude) * kLat
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

    var best = 0
    var bestT = 0.0
    var bestD = Double.MAX_VALUE
    for (i in 0 until path.size - 1) {
        val ax = x(path[i]); val ay = y(path[i])
        val dx = x(path[i + 1]) - ax; val dy = y(path[i + 1]) - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        val d = px * px + py * py
        if (d < bestD) { bestD = d; best = i; bestT = t }
    }

    val start = lerp(path[best], path[best + 1], bestT)
    val out = mutableListOf(start)
    var left = meters
    var prev = start
    for (i in best + 1 until path.size) {
        val next = path[i]
        val d = prev.distanceToAsDouble(next)
        if (d <= 0.0) continue
        if (d >= left) { out.add(lerp(prev, next, left / d)); break }
        out.add(next)
        left -= d
        prev = next
    }
    return out
}

@SuppressLint("MissingPermission")
@Composable
fun rememberLocation(): State<Location?> {
    val ctx = LocalContext.current
    val loc = remember { mutableStateOf<Location?>(null) }
    DisposableEffect(Unit) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { loc.value = r.lastLocation }
        }
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(cb) }
    }
    return loc
}

/** Compte à rebours en secondes. */
@Composable
fun rememberCountdown(seconds: Long): Long {
    val end = remember(seconds) { SystemClock.elapsedRealtime() + seconds * 1000 }
    var left by remember(seconds) { mutableLongStateOf(seconds) }
    LaunchedEffect(seconds) {
        while (left > 0) {
            delay(1000)
            left = ((end - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
        }
    }
    return left
}

@Composable
fun MapBox(
    modifier: Modifier,
    onTap: ((GeoPoint) -> Unit)? = null,
    update: (MapView) -> Unit
) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            controller.setZoom(18.0)
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
            if (onTap != null) overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap(p); return true }
                override fun longPressHelper(p: GeoPoint) = false
            }))
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    AndroidView(factory = { map }, modifier = modifier, update = { update(it) })
}

// =====================================================================
//  Thème et composants
// =====================================================================
private val Muted = Color(0xFF7A5563)

@Composable
fun SurpriseTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = Color(0xFFD81B60),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9E3),
        onPrimaryContainer = Color(0xFF3E001D),
        secondary = Color(0xFFFF7043),
        background = Color(0xFFFFF7F9),
        surface = Color.White,
        surfaceVariant = Color(0xFFFCE4EC),
        error = Color(0xFFD32F2F)
    ),
    content = content
)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Card(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
) { Column(Modifier.padding(16.dp), content = content) }

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
        else Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoScreen(
    emoji: String, title: String, text: String,
    button: String? = null, onClick: () -> Unit = {}
) = Column(
    modifier = Modifier.fillMaxSize().padding(28.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
) {
    Text(emoji, fontSize = 72.sp)
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(text, textAlign = TextAlign.Center, color = Muted)
    if (button != null) {
        Spacer(Modifier.height(24.dp))
        PrimaryButton(button, onClick = onClick)
    }
}

// =====================================================================
//  Activité et navigation
// =====================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = File(cacheDir, "tiles")
        }
        setContent { SurpriseTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, fine) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.any { v -> v }
    }
    var gps by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            gps = ctx.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
            delay(1500)
        }
    }
    var screen by remember { mutableStateOf("home") }
    if (screen != "home") BackHandler { screen = "home" }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFE3EC), Color(0xFFFFF7F9))))
            .systemBarsPadding()
    ) {
        when {
            !granted -> InfoScreen(
                "📍", "Autorisez la localisation",
                "Surprise a besoin de votre position pour cacher et retrouver les surprises.",
                "Autoriser"
            ) { ask.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            !gps -> InfoScreen(
                "🛰️", "Activez votre GPS",
                "Le GPS doit être activé pour continuer.",
                "Ouvrir les paramètres"
            ) { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            screen == "hide" -> HideScreen { screen = "home" }
            screen == "find" -> FindScreen { screen = "home" }
            else -> Home(onHide = { screen = "hide" }, onFind = { screen = "find" })
        }
    }
}

@Composable
fun ActionCard(emoji: String, title: String, sub: String, onClick: () -> Unit) =
    SoftCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 36.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
    }

@Composable
fun Home(onHide: () -> Unit, onFind: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("🎁", fontSize = 80.sp)
        Text(
            "Surprise ♥️", style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text("Cachez un cadeau et guidez quelqu'un jusqu'à lui.", textAlign = TextAlign.Center, color = Muted)
        Spacer(Modifier.height(40.dp))
        ActionCard("🎁", "Cacher une surprise", "Choisissez le lieu, les photos et la durée", onHide)
        Spacer(Modifier.height(16.dp))
        ActionCard("🔎", "Trouver une surprise", "Entrez le code à 14 chiffres", onFind)
    }
}

// =====================================================================
//  Cacher une surprise (3 étapes)
// =====================================================================
@Composable
fun PhotoSlot(label: String, bmp: Bitmap?, modifier: Modifier, onBitmap: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val file = remember { File(ctx.cacheDir, "img_${System.nanoTime()}.jpg") }
    val uri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", file) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) onBitmap(loadPhoto(file.path))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFFCE4EC))
                .clickable { cam.launch(uri) },
            contentAlignment = Alignment.Center
        ) {
            if (bmp == null) Text("📷", fontSize = 32.sp)
            else Image(bmp.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
        Text(if (bmp == null) label else "✅ $label", fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun HideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by rememberLocation()
    var step by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    val photos = remember { mutableStateListOf<Bitmap?>(null, null, null) }
    var hint by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(1440) }
    var busy by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    val centered = remember { BooleanArray(1) }

    BackHandler(enabled = step > 0 && code == null) { step-- }

    val done = code
    if (done != null) {
        SuccessScreen(done, minutes, onBack)
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Cacher une surprise") { if (step > 0) step-- else onBack() }
        LinearProgressIndicator(
            progress = { (step + 1) / 3f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
        )
        Text(
            "Étape ${step + 1} sur 3", style = MaterialTheme.typography.labelLarge, color = Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        when (step) {
            0 -> {
                Text(
                    "📍 Touchez la carte à l'endroit où vous cachez la surprise",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                MapBox(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    onTap = { picked = it }
                ) { m ->
                    if (!centered[0]) {
                        (picked ?: me?.let { GeoPoint(it) })?.let { m.controller.setCenter(it); centered[0] = true }
                    }
                    m.overlays.removeAll { it is Marker }
                    picked?.let { p -> m.overlays.add(Marker(m).apply { position = p; title = "🎁" }) }
                    m.invalidate()
                }
            }
            1 -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            ) {
                Text("📸 3 photos pour guider la personne", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Une vue large, une vue plus proche, puis l'endroit exact de la cachette.", color = Muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Vue large", "Plus proche", "La cachette").forEachIndexed { i, label ->
                        PhotoSlot(label, photos[i], Modifier.weight(1f)) { photos[i] = it }
                    }
                }
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)
            ) {
                OutlinedTextField(
                    hint, { hint = it }, label = { Text("💡 Indice") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    msg, { msg = it }, label = { Text("💌 Message") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), minLines = 3
                )
                Spacer(Modifier.height(20.dp))
                Text("⏳ Temps pour trouver la surprise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Passé ce délai, le code expire.", color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { (label, m) ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                    }
                }
            }
        }

        Column(Modifier.padding(20.dp)) {
            if (sendError) Text(
                "Envoi impossible. Vérifiez votre connexion Internet et réessayez.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val canNext = when (step) {
                0 -> picked != null
                1 -> photos.all { it != null }
                else -> !busy
            }
            PrimaryButton(
                if (step < 2) "Continuer" else if (busy) "Envoi…" else "FAIT ✔",
                enabled = canNext
            ) {
                if (step < 2) step++ else {
                    busy = true
                    sendError = false
                    scope.launch {
                        val r = runCatching {
                            val enc = withContext(Dispatchers.Default) { photos.map { it!!.toB64() } }
                            Repo.save(picked!!.latitude, picked!!.longitude, hint, msg, enc, minutes)
                        }
                        code = r.getOrNull()
                        sendError = r.isFailure
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
fun SuccessScreen(code: String, minutes: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎉", fontSize = 72.sp)
        Text("Surprise cachée !", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Text("Votre code", color = Muted)
            Text(
                code.chunked(4).joinToString(" "),
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text("⏳ Valable ${durationLabel(minutes)}")
            Text("🔒 Il expire aussi dès que la surprise est trouvée.", color = Muted)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Copier le code") { clip.setText(AnnotatedString(code)) }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Partager") {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "🎁 J'ai caché une surprise pour toi ! Code dans l'appli Surprise ♥️ : $code (valable ${durationLabel(minutes)})"
                )
            }
            ctx.startActivity(Intent.createChooser(i, "Partager le code"))
        }
        TextButton(onClick = onDone) { Text("Retour à l'accueil") }
    }
}

// =====================================================================
//  Trouver une surprise
// =====================================================================
@Composable
fun FindScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf<Goal?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val g = goal
    if (g != null) {
        Guide(code, g, onBack)
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Trouver une surprise", onBack)
        Column(Modifier.padding(24.dp)) {
            Text("🔎", fontSize = 56.sp)
            Text("Entrez le code à 14 chiffres", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(14); message = null },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = TextStyle(fontSize = 22.sp, letterSpacing = 2.sp),
                shape = RoundedCornerShape(16.dp),
                supportingText = { Text("${code.length}/14") },
                modifier = Modifier.fillMaxWidth()
            )
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (loading) "Vérification…" else "C'est parti !", enabled = code.length == 14 && !loading) {
                scope.launch {
                    loading = true
                    message = null
                    runCatching { Repo.check(code) }
                        .onSuccess { (st, tg) ->
                            when (st) {
                                "ok" -> goal = tg
                                "found" -> message = "Cette surprise a déjà été trouvée : le code a expiré."
                                "expired" -> message = "Le temps est écoulé : ce code a expiré."
                                else -> message = "Code introuvable. Vérifiez les 14 chiffres."
                            }
                        }
                        .onFailure { message = "Connexion impossible. Vérifiez votre connexion Internet et réessayez." }
                    loading = false
                }
            }
        }
    }
}

@Composable
fun Guide(code: String, goal: Goal, onExit: () -> Unit) {
    val loc by rememberLocation()
    val target = remember { GeoPoint(goal.lat, goal.lon) }
    val left = rememberCountdown(goal.secondsLeft)
    val total = remember { maxOf(goal.secondsLeft, 1L) }
    var path by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var phase by remember { mutableStateOf("guide") } // guide, claiming, neterror, done, found, expired
    var reveal by remember { mutableStateOf<Reveal?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val centered = remember { BooleanArray(1) }

    val dist = loc?.let {
        val r = FloatArray(1)
        Location.distanceBetween(it.latitude, it.longitude, goal.lat, goal.lon, r); r[0]
    }
    val arrived = dist != null && dist < 30f

    // Recalcule le chemin quand l'utilisateur se déplace d'environ 50 m
    val cell = loc?.let { (it.latitude * 2000).toInt() to (it.longitude * 2000).toInt() }
    LaunchedEffect(cell) { loc?.let { path = Repo.route(GeoPoint(it), target) } }

    // Temps écoulé
    LaunchedEffect(left) {
        if (left <= 0L && (phase == "guide" || phase == "neterror")) phase = "expired"
    }

    // Arrivée : le serveur vérifie la position, donne le contenu et fait expirer le code
    LaunchedEffect(arrived, retry) {
        while (arrived && (phase == "guide" || phase == "neterror")) {
            val l = loc ?: break
            phase = "claiming"
            val r = runCatching { Repo.claim(code, l.latitude, l.longitude) }.getOrNull()
            when (r?.first) {
                null -> { phase = "neterror"; break }
                "ok" -> { reveal = r?.second; phase = "done" }
                "found" -> phase = "found"
                "expired" -> phase = "expired"
                else -> { phase = "guide"; delay(4000) }
            }
        }
    }

    when (phase) {
        "done" -> reveal?.let { RevealScreen(it, onExit) }
        "found" -> InfoScreen("✅", "Déjà trouvée", "Cette surprise a déjà été trouvée : le code a expiré.", "Retour à l'accueil", onExit)
        "expired" -> InfoScreen("⏰", "Temps écoulé", "Le délai est dépassé : ce code a expiré.", "Retour à l'accueil", onExit)
        else -> {
            val urgent = left < 300
            val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Column(Modifier.fillMaxSize()) {
                SoftCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("⏳ Temps restant", style = MaterialTheme.typography.labelLarge, color = Muted)
                            Text(fmt(left), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
                        }
                        TextButton(onClick = onExit) { Text("Quitter") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { left.toFloat() / total },
                        color = accent,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
                MapBox(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
                ) { m ->
                    m.overlays.clear()
                    val ahead = loc?.let { lineAhead(path, GeoPoint(it)) } ?: emptyList()
                    if (ahead.size > 1) m.overlays.add(Polyline().apply {
                        setPoints(ahead); outlinePaint.strokeWidth = 12f
                    })
                    loc?.let { l ->
                        m.overlays.add(Marker(m).apply { position = GeoPoint(l); title = "Moi" })
                        if (!centered[0] || !m.boundingBox.contains(l.latitude, l.longitude)) {
                            m.controller.setCenter(GeoPoint(l)); centered[0] = true
                        }
                    }
                    m.invalidate()
                }
                SoftCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        when {
                            phase == "claiming" -> "🎁 Vous y êtes presque… vérification en cours"
                            phase == "neterror" -> "📡 Connexion perdue. Vérifiez Internet puis réessayez."
                            dist == null -> "📡 Recherche de votre position…"
                            else -> "👣 Suivez la ligne : elle s'allonge quand vous avancez"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    if (phase == "neterror") {
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Réessayer") { retry++ }
                    }
                }
            }
        }
    }
}

@Composable
fun RevealScreen(r: Reveal, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎉", fontSize = 72.sp)
        Text(
            "Vous êtes arrivé !", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        r.photos.forEach { b ->
            val img = remember(b) {
                val bytes = Base64.decode(b, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            img?.let {
                Image(
                    it, null,
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        if (r.hint.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            SoftCard(Modifier.fillMaxWidth()) {
                Text("💡 Indice", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text(r.hint, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (r.message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SoftCard(Modifier.fillMaxWidth()) {
                Text("💌 Message", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text(r.message)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("🔒 Ce code est maintenant expiré.", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Terminer", onClick = onExit)
    }
}
