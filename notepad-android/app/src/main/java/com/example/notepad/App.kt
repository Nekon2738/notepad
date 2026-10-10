package com.example.notepad

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- state

fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

fun familyFor(repo: Repo, key: String): FontFamily = when (key) {
    "sans" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> repo.fontFile(key)?.let { f -> runCatching { FontFamily(Font(f)) }.getOrNull() } ?: FontFamily.SansSerif
}

class AppState(ctx: Context) {
    val repo = Repo(ctx)
    private val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val notes = mutableStateListOf<Note>()
    var editing by mutableStateOf<Note?>(null)
    var settings by mutableStateOf(false)
    var browser by mutableStateOf(false)
    var theme by mutableStateOf(prefs.getString("theme", "auto") ?: "auto")
    var accent by mutableStateOf(prefs.getInt("accent", 0xFF5B7CFA.toInt()))
    var font by mutableStateOf(prefs.getString("font", "sans") ?: "sans")
    var size by mutableStateOf(prefs.getFloat("size", 18f))
    var lh by mutableStateOf(prefs.getFloat("lh", 1.6f))
    var fonts by mutableStateOf(repo.fonts())

    init {
        notes.addAll(repo.loadAll())
    }

    fun persist() {
        prefs.edit().putString("theme", theme).putInt("accent", accent).putString("font", font)
            .putFloat("size", size).putFloat("lh", lh).apply()
    }

    fun upsert(n: Note) {
        val m = n.copy(updated = System.currentTimeMillis())
        val i = notes.indexOfFirst { it.id == n.id }
        if (i >= 0) notes[i] = m else notes.add(0, m)
        repo.save(m)
    }

    fun remove(n: Note) {
        notes.removeAll { it.id == n.id }
        repo.delete(n)
    }

    fun family(): FontFamily = familyFor(repo, font)
}

// ---------------------------------------------------------------- theme + root

@Composable
fun AppTheme(app: AppState, content: @Composable () -> Unit) {
    val dark = when (app.theme) {
        "dark" -> true
        "light", "sepia" -> false
        else -> isSystemInDarkTheme()
    }
    val acc = Color(app.accent)
    val cs = when {
        app.theme == "sepia" -> lightColorScheme(
            primary = acc, background = Color(0xFFF4ECD8), surface = Color(0xFFFBF5E6),
            onBackground = Color(0xFF43372A), onSurface = Color(0xFF43372A),
            surfaceVariant = Color(0xFFE6DBC0), onSurfaceVariant = Color(0xFF9A8A72)
        )
        dark -> darkColorScheme(
            primary = acc, background = Color(0xFF141415), surface = Color(0xFF1D1D1F),
            onBackground = Color(0xFFE8E8E4), onSurface = Color(0xFFE8E8E4),
            surfaceVariant = Color(0xFF2A2A2D), onSurfaceVariant = Color(0xFF8D8D88)
        )
        else -> lightColorScheme(
            primary = acc, background = Color(0xFFFAFAF8), surface = Color.White,
            onBackground = Color(0xFF232321), onSurface = Color(0xFF232321),
            surfaceVariant = Color(0xFFECECE6), onSurfaceVariant = Color(0xFF8B8B85)
        )
    }
    MaterialTheme(colorScheme = cs, content = content)
}

@Composable
fun Root(app: AppState) {
    AppTheme(app) {
        val bg by animateColorAsState(MaterialTheme.colorScheme.background, tween(400), label = "bg")
        Box(Modifier.fillMaxSize().background(bg).systemBarsPadding().imePadding()) {
            AnimatedContent(
                targetState = app.editing,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320))) togetherWith fadeOut(tween(200))
                    } else {
                        fadeIn(tween(320)) togetherWith (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut(tween(250)))
                    }
                },
                label = "screen"
            ) { n ->
                if (n == null) ListScreen(app) else EditorScreen(app, n)
            }
            AnimatedVisibility(
                visible = app.settings,
                enter = slideInVertically(tween(350)) { it } + fadeIn(tween(250)),
                exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(250))
            ) { SettingsScreen(app) }
            AnimatedVisibility(visible = app.browser, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                BrowserScreen(app)
            }
        }
    }
}

// ---------------------------------------------------------------- small widgets

@Composable
fun Tap(
    t: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    onClick: () -> Unit
) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, fontSize = 20.sp, color = color) }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary else cs.surface, label = "pill")
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (selected) Color.White else cs.onSurface, fontSize = 14.sp) }
}

@Composable
fun Label(t: String) {
    Text(
        t, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)
    )
}

// ---------------------------------------------------------------- list

@Composable
fun ListScreen(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var last: Note? = null
            for (u in uris) {
                val r = withContext(Dispatchers.IO) { runCatching { app.repo.readImported(u) }.getOrNull() }
                if (r == null) {
                    toast(ctx, "Не удалось открыть файл (поддерживаются txt, md, html, docx)")
                    continue
                }
                val n = Note(title = r.first, blocks = listOf(Block.Text(text = r.second)))
                app.upsert(n)
                last = n
            }
            last?.let { app.editing = it }
        }
    }

    val list = app.notes.sortedByDescending { it.updated }
        .filter { q.isBlank() || it.title.contains(q, true) || it.toPlain().contains(q, true) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Заметки", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground, modifier = Modifier.weight(1f))
                Tap("📥") { picker.launch(arrayOf("*/*")) }
                Tap("⚙") { app.settings = true }
            }
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (q.isEmpty()) Text("Поиск", color = cs.onSurfaceVariant, fontSize = 16.sp)
                        inner()
                    }
                }
            )
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (app.notes.isEmpty()) "Пока пусто.\nНажмите + чтобы начать" else "Ничего не найдено",
                        color = cs.onSurfaceVariant, fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(list, key = { it.id }) { n ->
                        NoteCard(n, Modifier.animateItem()) { app.editing = n }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { app.editing = Note() },
            containerColor = cs.primary, contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Text("+", fontSize = 30.sp, color = Color.White) }
    }
}

@Composable
fun NoteCard(n: Note, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val preview = n.blocks.filterIsInstance<Block.Text>().firstOrNull { it.text.isNotBlank() }?.text?.take(200)?.trim() ?: ""
    val date = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(n.updated))
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface)
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Text(n.title.ifBlank { "Без названия" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (preview.isNotEmpty()) {
            Text(preview, fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Text(date, fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------- editor

class Player {
    var playing by mutableStateOf<String?>(null)
    private var mp: MediaPlayer? = null

    fun toggle(id: String, f: File) {
        val was = playing == id
        stop()
        if (was) return
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.setOnCompletionListener { playing = null }
            p.start()
            mp = p
            playing = id
        } catch (e: Exception) {
            playing = null
        }
    }

    fun stop() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        playing = null
    }
}

@Suppress("DEPRECATION")
fun newRecorder(ctx: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

fun decodeImage(f: File, max: Int = 1200): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= max) s *= 2
    val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val ori = runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val deg = when (ori) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    val out: Bitmap = if (deg != 0f) {
        Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
    } else bm
    return out.asImageBitmap()
}

@Composable
fun ImageBlock(app: AppState, b: Block.Image, onRemove: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, b.file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeImage(File(app.repo.mediaDir, b.file)) }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AnimatedVisibility(visible = bmp != null, enter = fadeIn(tween(400))) {
            bmp?.let {
                Image(it, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
            }
        }
        Tap("✕", Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x88000000), CircleShape), Color.White) { onRemove() }
    }
}

@Composable
fun AudioBlock(app: AppState, b: Block.Audio, player: Player, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = player.playing == b.id
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(cs.surface).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tap(if (on) "⏸" else "▶", color = cs.primary) { player.toggle(b.id, File(app.repo.mediaDir, b.file)) }
        Text(b.name, Modifier.weight(1f), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Tap("✕", color = cs.onSurfaceVariant) { player.stop(); onRemove() }
    }
}

@Composable
fun EditorScreen(app: AppState, initial: Note) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var note by remember { mutableStateOf(initial) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }
    var recName by remember { mutableStateOf("") }
    var export by remember { mutableStateOf<ByteArray?>(null) }
    val latest by rememberUpdatedState(note)
    val gone = remember { BooleanArray(1) }
    val player = remember { Player() }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pa"
    )

    LaunchedEffect(note) {
        delay(500)
        if (!gone[0] && note != initial && !note.isEmpty()) app.upsert(note)
    }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            rec?.let { runCatching { it.stop() }; it.release() }
            if (!gone[0]) {
                if (latest.isEmpty()) app.remove(latest) else if (latest != initial) app.upsert(latest)
            }
        }
    }
    BackHandler { app.editing = null }

    fun update(b: Block) {
        note = note.copy(blocks = note.blocks.map { if (it.id == b.id) b else it })
    }

    fun insert(b: Block) {
        val l = note.blocks.toMutableList()
        val i = l.indexOfFirst { it.id == focusedId }.let { if (it < 0) l.lastIndex else it }
        l.add(i + 1, b)
        if (l.getOrNull(i + 2) !is Block.Text) l.add(i + 2, Block.Text())
        note = note.copy(blocks = l)
    }

    fun removeBlock(b: Block) {
        when (b) {
            is Block.Image -> File(app.repo.mediaDir, b.file).delete()
            is Block.Audio -> File(app.repo.mediaDir, b.file).delete()
            else -> {}
        }
        note = note.copy(blocks = note.blocks.filter { it.id != b.id })
    }

    val imgPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { app.repo.copyMedia(u, "jpg") }.getOrNull() }
            if (f != null) insert(Block.Image(file = f)) else toast(ctx, "Не удалось добавить фото")
        }
    }
    val audPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val nm = app.repo.displayName(u)
                    app.repo.copyMedia(u, nm.substringAfterLast('.', "mp3")) to nm
                }.getOrNull()
            }
            if (r != null) insert(Block.Audio(file = r.first, name = r.second)) else toast(ctx, "Не удалось добавить аудио")
        }
    }

    fun startRec() {
        try {
            val name = newId() + ".m4a"
            val r = newRecorder(ctx)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(File(app.repo.mediaDir, name).absolutePath)
            r.prepare()
            r.start()
            rec = r
            recName = name
        } catch (e: Exception) {
            toast(ctx, "Не удалось начать запись")
        }
    }

    fun stopRec() {
        val r = rec ?: return
        runCatching { r.stop() }
        r.release()
        rec = null
        val label = "Запись " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        insert(Block.Audio(file = recName, name = label))
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else toast(ctx, "Нужно разрешение на микрофон")
    }

    fun saveTo(u: Uri?) {
        val d = export
        export = null
        if (u != null && d != null) scope.launch(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(u)?.use { it.write(d) }
        }
    }

    val exTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { saveTo(it) }
    val exMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { saveTo(it) }
    val exHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { saveTo(it) }
    val exDocx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { saveTo(it) }

    fun doExport(l: ActivityResultLauncher<String>, ext: String, make: () -> ByteArray) {
        menu = false
        val fname = note.title.ifBlank { "Заметка" }.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60)
        scope.launch {
            export = withContext(Dispatchers.IO) { make() }
            l.launch("$fname.$ext")
        }
    }

    val fam = app.family()
    fun style(h: Boolean) = TextStyle(
        fontFamily = fam,
        fontSize = (if (h) app.size * 1.4f else app.size).sp,
        fontWeight = if (h) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = (app.size * app.lh * (if (h) 1.15f else 1f)).sp,
        color = cs.onBackground
    )
    val words = remember(note.blocks) {
        note.blocks.sumOf { b -> if (b is Block.Text) b.text.split(Regex("\\s+")).count { it.isNotBlank() } else 0 }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tap("←") { app.editing = null }
            Spacer(Modifier.weight(1f))
            Text("$words слов", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Box {
                Tap("⋯") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Сохранить как .txt") }, onClick = { doExport(exTxt, "txt") { note.toPlain().toByteArray() } })
                    DropdownMenuItem(text = { Text("Сохранить как .md") }, onClick = { doExport(exMd, "md") { note.toMarkdown().toByteArray() } })
                    DropdownMenuItem(text = { Text("Сохранить как .html") }, onClick = { doExport(exHtml, "html") { note.toHtml(app.repo.mediaDir).toByteArray() } })
                    DropdownMenuItem(text = { Text("Сохранить как .docx") }, onClick = { doExport(exDocx, "docx") { docxBytes(note.toPlain()) } })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; confirm = true })
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            item {
                BasicTextField(
                    value = note.title, onValueChange = { note = note.copy(title = it) },
                    textStyle = TextStyle(fontFamily = fam, fontSize = (app.size * 1.6f).sp, fontWeight = FontWeight.Bold, color = cs.onBackground),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (note.title.isEmpty()) Text("Заголовок", fontFamily = fam, fontSize = (app.size * 1.6f).sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
                            inner()
                        }
                    }
                )
            }
            items(note.blocks, key = { it.id }) { b ->
                when (b) {
                    is Block.Text -> BasicTextField(
                        value = b.text, onValueChange = { update(b.copy(text = it)) },
                        textStyle = style(b.heading), cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedId = b.id },
                        decorationBox = { inner ->
                            Box {
                                if (b.text.isEmpty() && note.blocks.size == 1) {
                                    Text("Начните писать…", style = style(false).copy(color = cs.onSurfaceVariant.copy(alpha = 0.6f)))
                                }
                                inner()
                            }
                        }
                    )
                    is Block.Image -> ImageBlock(app, b) { removeBlock(b) }
                    is Block.Audio -> AudioBlock(app, b, player) { removeBlock(b) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            Tap("H") {
                val t = (note.blocks.firstOrNull { it.id == focusedId } as? Block.Text)
                    ?: (note.blocks.firstOrNull { it is Block.Text } as? Block.Text)
                if (t != null) update(t.copy(heading = !t.heading))
            }
            Tap("🖼") { imgPick.launch("image/*") }
            Tap("🎙", Modifier.alpha(if (rec != null) pulse else 1f)) {
                if (rec != null) stopRec()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                else perm.launch(Manifest.permission.RECORD_AUDIO)
            }
            Tap("🎵") { audPick.launch("audio/*") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить заметку?") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    gone[0] = true
                    app.remove(note)
                    app.editing = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun FontRow(name: String, fam: FontFamily, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Аа Привет, мир", fontFamily = fam, fontSize = 20.sp, color = cs.onBackground)
            Text(name, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        if (selected) Text("✓", color = cs.primary, fontSize = 20.sp)
        if (onDelete != null) Tap("✕", color = cs.onSurfaceVariant) { onDelete() }
    }
}

@Composable
fun SettingsScreen(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    BackHandler { app.settings = false }

    val fontPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { app.repo.addFontFromUri(u) }.getOrDefault(0) }
            app.fonts = app.repo.fonts()
            toast(ctx, if (n > 0) "Шрифтов добавлено: $n" else "Нужен файл .ttf, .otf или .zip со шрифтами")
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tap("←") { app.settings = false }
            Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
        }
        Label("Тема")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Авто", "light" to "Светлая", "dark" to "Тёмная", "sepia" to "Сепия").forEach { (k, v) ->
                Pill(v, app.theme == k, Modifier.weight(1f)) { app.theme = k; app.persist() }
            }
        }
        Label("Цвет акцента")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0xFF5B7CFA, 0xFFE5634D, 0xFF2FA37A, 0xFFC9892B, 0xFFA35BD6, 0xFF555555).forEach { c ->
                val ci = c.toInt()
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(ci))
                        .border(if (app.accent == ci) 3.dp else 0.dp, cs.onBackground, CircleShape)
                        .clickable { app.accent = ci; app.persist() }
                )
            }
        }
        Label("Шрифт")
        listOf("sans" to "Обычный", "serif" to "С засечками", "mono" to "Моноширинный", "cursive" to "Рукописный").forEach { (k, v) ->
            FontRow(v, familyFor(app.repo, k), app.font == k) { app.font = k; app.persist() }
        }
        app.fonts.forEach { f ->
            FontRow(f.nameWithoutExtension, familyFor(app.repo, f.name), app.font == f.name, onDelete = {
                f.delete()
                app.fonts = app.repo.fonts()
                if (app.font == f.name) app.font = "sans"
                app.persist()
            }) { app.font = f.name; app.persist() }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Шрифт из файла", false, Modifier.weight(1f)) { fontPick.launch("*/*") }
            Pill("Браузер шрифтов", false, Modifier.weight(1f)) { app.browser = true }
        }
        Label("Размер текста")
        Slider(value = app.size, onValueChange = { app.size = it }, valueRange = 14f..30f, onValueChangeFinished = { app.persist() })
        Label("Межстрочный интервал")
        Slider(value = app.lh, onValueChange = { app.lh = it }, valueRange = 1.2f..2.4f, onValueChangeFinished = { app.persist() })
        Text(
            "Так будет выглядеть ваш текст. Съешь ещё этих мягких французских булок.",
            fontFamily = app.family(), fontSize = app.size.sp, lineHeight = (app.size * app.lh).sp,
            color = cs.onBackground, modifier = Modifier.padding(top = 12.dp, bottom = 40.dp)
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var status by remember { mutableStateOf("Скачайте шрифт (.ttf, .otf или .zip), он добавится сам") }
    BackHandler { app.browser = false }
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tap("←") { app.browser = false }
            Text(status, Modifier.weight(1f).padding(end = 12.dp), color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    setDownloadListener { url, _, cd, mime, _ ->
                        val name = URLUtil.guessFileName(url, cd, mime)
                        status = "Загрузка…"
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                runCatching { URL(url).openStream().use { app.repo.saveFontStream(name, it) } }.getOrDefault(0)
                            }
                            app.fonts = app.repo.fonts()
                            status = if (n > 0) "Добавлено шрифтов: $n. Выберите в настройках" else "Не получилось: это не шрифт или загрузка закрыта"
                        }
                    }
                    loadUrl("https://fonts.google.com")
                }
            }
        )
    }
}
