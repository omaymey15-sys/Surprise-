package com.surprise.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.*
import androidx.compose.foundation.text.selection.SelectionContainer
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

// ---------- Données ----------
class Surprise(
    val lat: Double, val lon: Double,
    val hint: String, val message: String,
    val photos: List<String>
)

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
                val status = c.responseCode
                val text = (if (status in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                status to text
            } finally {
                c.disconnect()
            }
        }

    /** Enregistre la surprise et renvoie le code à 14 chiffres. */
    suspend fun save(lat: Double, lon: Double, hint: String, msg: String, photos: List<String>): String {
        val rnd = SecureRandom()
        repeat(5) {
            val code = (1..14).joinToString("") { rnd.nextInt(10).toString() }
            val body = JSONObject().put("p_code", code).put("p_lat", lat).put("p_lon", lon)
                .put("p_hint", hint).put("p_msg", msg).put("p_photos", JSONArray(photos))
            val (status, _) = rpc("create_surprise", body)
            if (status in 200..299) return code
            if (status != 409) throw IllegalStateException("HTTP $status") // 409 = code déjà pris : on réessaie
        }
        throw IllegalStateException("Impossible de générer un code")
    }

    /** Renvoie null si le code n'existe pas ; lève une exception si la connexion échoue. */
    suspend fun load(code: String): Surprise? {
        val (status, text) = rpc("get_surprise", JSONObject().put("p_code", code))
        if (status !in 200..299) throw IllegalStateException("HTTP $status")
        val rows = JSONArray(text)
        if (rows.length() == 0) return null
        val j = rows.getJSONObject(0)
        val arr = j.getJSONArray("photos")
        return Surprise(
            j.getDouble("lat"), j.getDouble("lon"),
            j.optString("hint"), j.optString("msg"),
            List(arr.length()) { arr.getString(it) }
        )
    }

    /** Itinéraire à pied (OSM). Si ça échoue : ligne droite. */
    suspend fun route(a: GeoPoint, b: GeoPoint): List<GeoPoint> = withContext(Dispatchers.IO) {
        runCatching {
            val u = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/" +
                "${a.longitude},${a.latitude};${b.longitude},${b.latitude}?overview=full&geometries=geojson"
            val c = JSONObject(URL(u).readText()).getJSONArray("routes").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            List(c.length()) { GeoPoint(c.getJSONArray(it).getDouble(1), c.getJSONArray(it).getDouble(0)) }
        }.getOrDefault(listOf(a, b))
    }
}

// ---------- Utilitaires ----------
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
            controller.setZoom(17.0)
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

// ---------- Activité ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = File(cacheDir, "tiles")
        }
        setContent { MaterialTheme { Surface { App() } } }
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content
)

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

    when {
        !granted -> Centered {
            Text("📍 La localisation est nécessaire", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button({ ask.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Autoriser") }
        }
        !gps -> Centered {
            Text("🛰️ Activez votre GPS pour continuer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button({ ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Ouvrir les paramètres") }
        }
        screen == "hide" -> HideScreen()
        screen == "find" -> FindScreen()
        else -> Centered {
            Text("Surprise ♥️", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(32.dp))
            Button({ screen = "hide" }, Modifier.fillMaxWidth()) { Text("🎁 Cacher une surprise") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ screen = "find" }, Modifier.fillMaxWidth()) { Text("🔎 Trouver une surprise") }
        }
    }
}

// ---------- Cacher ----------
@Composable
fun PhotoSlot(label: String, bmp: Bitmap?, onBitmap: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val file = remember { File(ctx.cacheDir, "img_${System.nanoTime()}.jpg") }
    val uri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", file) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) onBitmap(loadPhoto(file.path))
    }
    OutlinedButton({ cam.launch(uri) }) { Text(if (bmp == null) "📷 $label" else "✅ $label") }
}

@Composable
fun HideScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by rememberLocation()
    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    val photos = remember { mutableStateListOf<Bitmap?>(null, null, null) }
    var hint by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    val centered = remember { BooleanArray(1) }

    if (code != null) {
        Centered {
            Text("✅ Surprise cachée !", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            SelectionContainer { Text(code!!, style = MaterialTheme.typography.displaySmall) }
            Spacer(Modifier.height(16.dp))
            Button({
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "🎁 J'ai caché une surprise pour toi ! Code dans l'appli Surprise ♥️ : $code")
                }
                ctx.startActivity(Intent.createChooser(i, "Partager le code"))
            }) { Text("Partager le code") }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("1. Touchez la carte pour choisir l'endroit", style = MaterialTheme.typography.titleMedium)
        MapBox(Modifier.fillMaxWidth().height(300.dp), onTap = { picked = it }) { m ->
            me?.let { if (!centered[0]) { m.controller.setCenter(GeoPoint(it)); centered[0] = true } }
            m.overlays.removeAll { it is Marker }
            picked?.let { p -> m.overlays.add(Marker(m).apply { position = p; title = "🎁" }) }
            m.invalidate()
        }
        Spacer(Modifier.height(12.dp))
        Text("2. Trois photos de l'endroit", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i -> PhotoSlot("Photo ${i + 1}", photos[i]) { photos[i] = it } }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(hint, { hint = it }, label = { Text("Indice") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(msg, { msg = it }, label = { Text("Message / texte") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        if (sendError) Text(
            "Envoi impossible. Vérifiez votre connexion Internet et réessayez.",
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = picked != null && photos.all { it != null } && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                sendError = false
                scope.launch {
                    val r = runCatching {
                        val enc = withContext(Dispatchers.Default) { photos.map { it!!.toB64() } }
                        Repo.save(picked!!.latitude, picked!!.longitude, hint, msg, enc)
                    }
                    code = r.getOrNull()
                    sendError = r.isFailure
                    busy = false
                }
            }
        ) { Text(if (busy) "Envoi…" else "FAIT ✔") }
    }
}

// ---------- Trouver ----------
@Composable
fun FindScreen() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var s by remember { mutableStateOf<Surprise?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    if (s == null) {
        Centered {
            Text("Entrez le code à 14 chiffres", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                code, { code = it.filter(Char::isDigit).take(14) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(enabled = code.length == 14 && !loading, onClick = {
                scope.launch {
                    loading = true
                    error = null
                    runCatching { Repo.load(code) }
                        .onSuccess { s = it; if (it == null) error = "Code introuvable" }
                        .onFailure { error = "Connexion impossible. Vérifiez votre connexion Internet et réessayez." }
                    loading = false
                }
            }) { Text(if (loading) "Chargement…" else "Go !") }
        }
    } else Guide(s!!)
}

@Composable
fun Guide(s: Surprise) {
    val loc by rememberLocation()
    val target = remember { GeoPoint(s.lat, s.lon) }
    var path by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    val centered = remember { BooleanArray(1) }

    val dist = loc?.let {
        val r = FloatArray(1)
        Location.distanceBetween(it.latitude, it.longitude, s.lat, s.lon, r); r[0]
    }
    // Recalcule le chemin quand l'utilisateur se déplace d'environ 50 m
    val cell = loc?.let { (it.latitude * 2000).toInt() to (it.longitude * 2000).toInt() }
    LaunchedEffect(cell) { loc?.let { path = Repo.route(GeoPoint(it), target) } }

    if (dist != null && dist < 30f) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("🎉 Vous êtes arrivé !", style = MaterialTheme.typography.headlineMedium)
            s.photos.forEach { b ->
                val img = remember(b) {
                    val bytes = Base64.decode(b, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }
                Image(img, null, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            Text("💡 Indice : ${s.hint}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(s.message)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            if (dist == null) "Recherche de votre position…" else "Il reste environ ${dist.toInt()} m",
            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium
        )
        MapBox(Modifier.fillMaxWidth().weight(1f)) { m ->
            m.overlays.clear()
            if (path.size > 1) m.overlays.add(Polyline().apply {
                setPoints(path); outlinePaint.strokeWidth = 12f
            })
            m.overlays.add(Marker(m).apply { position = target; title = "🎁" })
            loc?.let { l ->
                m.overlays.add(Marker(m).apply { position = GeoPoint(l); title = "Moi" })
                if (!centered[0]) { m.controller.setCenter(GeoPoint(l)); centered[0] = true }
            }
            m.invalidate()
        }
    }
}
