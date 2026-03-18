package com.example.nozerodays

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.nozerodays.ui.theme.NoZeroDaysTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

// --- Persistence Layer ---

@Entity(tableName = "day_records")
data class DayRecord(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val date: LocalDateTime,
    val completedHabits: Set<Int>
)

@Entity(tableName = "habit_names")
data class HabitNameEntity(
    @PrimaryKey val habitIndex: Int,
    val name: String
)

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): LocalDateTime? {
        return value?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
    }

    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? {
        return date?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    @TypeConverter
    fun fromSet(set: Set<Int>?): String? {
        return set?.joinToString(",")
    }

    @TypeConverter
    fun toSet(data: String?): Set<Int>? {
        if (data.isNullOrEmpty()) return emptySet()
        return data.split(",").map { it.toInt() }.toSet()
    }

    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun toUUID(uuid: String?): UUID? = uuid?.let { UUID.fromString(it) }
}

@Dao
interface DayRecordDao {
    @Query("SELECT * FROM day_records ORDER BY date ASC")
    fun getAll(): Flow<List<DayRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DayRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<DayRecord>)

    @Update
    suspend fun update(record: DayRecord)

    @Query("SELECT * FROM day_records WHERE date >= :start AND date < :end LIMIT 1")
    suspend fun getRecordForRange(start: LocalDateTime, end: LocalDateTime): DayRecord?

    @Query("SELECT * FROM day_records WHERE date >= :since ORDER BY date ASC")
    fun getRecent(since: LocalDateTime): Flow<List<DayRecord>>
}

@Dao
interface HabitNameDao {
    @Query("SELECT * FROM habit_names ORDER BY habitIndex ASC")
    fun getAll(): Flow<List<HabitNameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habitName: HabitNameEntity)
}

@Database(entities = [DayRecord::class, HabitNameEntity::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayRecordDao(): DayRecordDao
    abstract fun habitNameDao(): HabitNameDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `habit_names` (`habitIndex` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`habitIndex`))")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "no_zero_days_db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class HabitViewModel(applicationContext: Context) : ViewModel() {
    private val db = AppDatabase.getDatabase(applicationContext)
    private val dao = db.dayRecordDao()
    private val habitNameDao = db.habitNameDao()

    val history: StateFlow<List<DayRecord>> = dao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val defaultNames = habits.map { it.name }

    val habitNames: StateFlow<List<String>> = habitNameDao.getAll()
        .map { entities ->
            defaultNames.mapIndexed { index, default ->
                entities.find { it.habitIndex == index }?.name ?: default
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = defaultNames
        )

    fun renameHabit(index: Int, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            habitNameDao.insert(HabitNameEntity(habitIndex = index, name = newName))
        }
    }

    fun toggleHabit(record: DayRecord, habitIndex: Int) {
        val newHabits = if (record.completedHabits.contains(habitIndex)) {
            record.completedHabits - habitIndex
        } else {
            record.completedHabits + habitIndex
        }
        val updatedRecord = record.copy(completedHabits = newHabits)
        
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(updatedRecord)
        }
    }

    suspend fun ensureAllDaysExist() {
        withContext(Dispatchers.IO) {
            val allRecords = dao.getAll().first()
            val today = LocalDate.now()

            val startDate = if (allRecords.isEmpty()) today
                            else allRecords.minOf { it.date.toLocalDate() }

            val existingDates = allRecords.map { it.date.toLocalDate() }.toSet()

            val missing = mutableListOf<DayRecord>()
            var date = startDate
            while (!date.isAfter(today)) {
                if (!existingDates.contains(date)) {
                    missing.add(DayRecord(date = date.atStartOfDay(), completedHabits = emptySet()))
                }
                date = date.plusDays(1)
            }
            if (missing.isNotEmpty()) dao.insertAll(missing)
        }
    }

    // ┌──────────────────────────────────────────────────────────┐
    // │  DEBUG: Remove this entire block to disable dummy data   │
    // └──────────────────────────────────────────────────────────┘
    suspend fun seedDummyData() {
        withContext(Dispatchers.IO) {
            // Only seed if DB has fewer than 2 records (just today)
            val existing = dao.getAll().first()
            if (existing.size > 2) return@withContext

            val rng = Random(42) // Fixed seed for reproducible data
            val today = LocalDate.now()
            for (i in 24 downTo 1) {
                val date = today.minusDays(i.toLong()).atTime(12, 0)
                val habits = (0..3).filter { rng.nextFloat() > 0.4f }.toSet()
                dao.insert(DayRecord(date = date, completedHabits = habits))
            }
        }
    }
    // ── END DEBUG BLOCK ──
}

// --- UI Layer ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            NoZeroDaysTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    NoZeroDaysApp()
                }
            }
        }
    }
}

data class Habit(val name: String, val color: Color)
val habits = listOf(
    Habit("Art", Color(0xFFEB5353)), // TL
    Habit("Exercise", Color(0xFFD5BA26)),  // TR
    Habit("Study", Color(0xFF36AE7C)), // BL
    Habit("Meditate", Color(0xFF187498))   // BR
)

@Composable
fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoZeroDaysApp() {
    val context = LocalContext.current
    val viewModel: HabitViewModel = viewModel { HabitViewModel(context.applicationContext) }
    val history by viewModel.history.collectAsState()
    
    var timeRemaining by remember { mutableStateOf("00:00:00") }
    val habitNames by viewModel.habitNames.collectAsState()
    var showStats by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // Ensure we have at least one day to show (today)
    LaunchedEffect(Unit) {
        viewModel.seedDummyData() // DEBUG: Remove this line to disable dummy data
        viewModel.ensureAllDaysExist()
    }

    if (history.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = (history.size - 1).coerceAtLeast(0),
        pageCount = { history.size }
    )

    // Sync pager to last page when new days are added
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) pagerState.scrollToPage(history.size - 1)
    }

    val activeIndex = pagerState.currentPage.coerceIn(0, (history.size - 1).coerceAtLeast(0))
    val activeDay = history[activeIndex]

    // Countdown timer
    LaunchedEffect(Unit) {
        while (true) {
            val currentTime = LocalDateTime.now()
            val endOfDay = currentTime.toLocalDate().plusDays(1).atStartOfDay()
            val duration = Duration.between(currentTime, endOfDay)
            timeRemaining = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                duration.toHours(), (duration.toMinutes() % 60), (duration.seconds % 60)
            )
            delay(1000)
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // Observe the IME (keyboard) height so we can translate the entire layout upward
    val imeInsets = WindowInsets.ime
    val imeHeightPx = with(density) { imeInsets.getBottom(this).toFloat() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -imeHeightPx }
        ) {

            // ── 1. Header Section (Top Left) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 64.dp)
                    .noRippleClickable(enabled = activeIndex != history.size - 1) {
                        coroutineScope.launch { pagerState.animateScrollToPage(history.size - 1) }
                    }
            ) {
                val isToday = activeDay.date.toLocalDate() == LocalDate.now()
                val dayName = activeDay.date.format(DateTimeFormatter.ofPattern("EEEE"))
                val textMeasurer = rememberTextMeasurer()
                val baseStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 64.sp)
                // Measure "Ay" once to get stable metrics for the full 64sp size.
                // fixedRowHeightPx locks the layout footprint; its width is the
                // baseline for font-size shrinking.
                val baseMetrics = remember(density) {
                    textMeasurer.measure("Ay", baseStyle)
                }
                val fixedRowHeightPx = baseMetrics.size.height
                val dayFontSize = remember(dayName, screenWidth) {
                    val measured = textMeasurer.measure(dayName, baseStyle).size.width
                    // Available width = screen - margins(64dp) - arrow estimate(40dp)
                    val available = with(density) { (screenWidth - 104.dp).toPx() }
                    if (measured > available) 64.sp * (available / measured) else 64.sp
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.layout { measurable, constraints ->
                        // Measure unconstrained in height so glyphs (incl. descenders)
                        // are never clipped, then report the fixed 64sp height to the
                        // parent so nothing below ever shifts.
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE)
                        )
                        layout(placeable.width, fixedRowHeightPx) {
                            // Bottom-align: shift down so the content's bottom edge
                            // lands at fixedRowHeightPx.
                            placeable.placeRelative(0, fixedRowHeightPx - placeable.height)
                        }
                    }
                ) {
                    Text(
                        text = dayName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = dayFontSize,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isToday) {
                        Text(
                            text = "  ›",
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Normal,
                            fontSize = 36.sp,
                            modifier = Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                // Report fixedRowHeightPx so the Row's internal height is
                                // always stable, and center the glyph within that space.
                                val y = (fixedRowHeightPx - placeable.height) / 2
                                layout(placeable.width, fixedRowHeightPx) {
                                    placeable.placeRelative(0, y)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatCurrentDate(activeDay.date),
                    color = Color(0xFFC3C3C3),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isToday) "$timeRemaining still remaining" else "This day has ended",
                    color = Color(0xFFC3C3C3),
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(66.dp))

            // ── 2. Tagline & Stats Button ──
            Column(modifier = Modifier.padding(start = 32.dp)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFC3C3C3))) {
                            append("Make every day ")
                        }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                            append("count")
                        }
                    },
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(11.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        .noRippleClickable { showStats = true }
                        .padding(horizontal = 22.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "stats",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1.618f))

            // ── 3. Horizontal Timeline ──
            // Page slot sized so active circle (151dp) has a 41dp gap to its
            // immediate inactive neighbor (52dp). Inactive circles are then
            // shifted inward with graphicsLayer so their edge-to-edge gap is
            // also 41dp.
            run {
                val activeSize = 151.dp
                val inactiveSize = 52.dp
                val gap = 41.dp
                val pageSize = (activeSize / 2) + gap + (inactiveSize / 2) // 143.5dp
                val activeCenterX = screenWidth / 2
                val startPadding = activeCenterX - (pageSize / 2)
                val endPadding = (screenWidth - startPadding - pageSize).coerceAtLeast(0.dp)

                // How much to shift each successive inactive circle inward.
                // Page center-to-center is 110.5dp, but inactive-inactive should be 76dp.
                val inactiveStepReduction = with(density) { (pageSize - (inactiveSize + gap)).toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(activeSize),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalPager(
                        state = pagerState,
                        pageSize = PageSize.Fixed(pageSize),
                        contentPadding = PaddingValues(start = startPadding, end = endPadding),
                        beyondViewportPageCount = 10,
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            pagerSnapDistance = PagerSnapDistance.atMost(100)
                        ),
                        key = { history[it].id },
                        verticalAlignment = Alignment.CenterVertically
                    ) { pageIndex ->
                        val rawOffset = pageIndex - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        val absOffset = abs(rawOffset).coerceIn(0f, 1f)
                        val circleSize = lerp(activeSize, inactiveSize, absOffset)

                        // Shift inactive pages inward so their spacing compresses
                        // to maintain 41dp edge-to-edge gaps between inactive circles.
                        // Pages at distance 1 (immediate neighbor) need no shift. Each
                        // page beyond that shifts by inactiveStepReduction toward the active page.
                        val stepsToCompress = (abs(rawOffset) - 1f).coerceAtLeast(0f)
                        val direction = if (rawOffset < 0f) 1f else -1f
                        val translationXPx = direction * stepsToCompress * inactiveStepReduction

                        val isActive = absOffset < 0.01f
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(unbounded = true)
                                .graphicsLayer { translationX = translationXPx }
                                .then(
                                    if (!isActive) Modifier.noRippleClickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pageIndex)
                                        }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            QuadrantCircle(
                                completedHabits = history[pageIndex].completedHabits,
                                size = circleSize
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── 4. Habit Grid (Fixed at Bottom) ──
            HabitGrid(
                completedHabits = activeDay.completedHabits,
                habitNames = habitNames,
                onToggleHabit = { habitIndex ->
                    viewModel.toggleHabit(activeDay, habitIndex)
                },
                onRenameHabit = { index, newName ->
                    viewModel.renameHabit(index, newName)
                }
            )
        }

        // Stats Screen Overlay
        AnimatedVisibility(
            visible = showStats,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it })
        ) {
            StatsScreen(
                history = history,
                habitNames = habitNames,
                onClose = { showStats = false }
            )
        }
    }
}

@Composable
fun StatsScreen(
    history: List<DayRecord>,
    habitNames: List<String>,
    onClose: () -> Unit
) {
    val last28Days = history.takeLast(28)

    val totals = IntArray(4) { habitIndex ->
        history.count { it.completedHabits.contains(habitIndex) }
    }

    var verticalDragAmount by remember { mutableStateOf(0f) }

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (verticalDragAmount < -100f) {
                            onClose()
                        }
                        verticalDragAmount = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        verticalDragAmount += dragAmount
                    }
                )
            }
            .clickable(enabled = false) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Column {
                Text(
                    text = "TOTAL COUNTS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    totals.forEachIndexed { index, count ->
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = count.toString(),
                                color = habits[index].color,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = habitNames[index],
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            ThisYearSection(history, habitNames)

            Column {
                Text(
                    text = "LAST 4 WEEKS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            repeat(7) { colIndex ->
                                val recordIndex = rowIndex * 7 + colIndex
                                if (recordIndex < last28Days.size) {
                                    QuadrantCircle(
                                        completedHabits = last28Days[recordIndex].completedHabits,
                                        size = 20.dp
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            Column {
                Text(
                    text = "CONSISTENCY",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                ConsistencyGraph(history.takeLast(28))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ThisYearSection(history: List<DayRecord>, habitNames: List<String>) {
    val currentYear = LocalDate.now().year
    var yearOffset by remember { mutableStateOf(0) }
    val displayYear = currentYear + yearOffset

    val yearCounts = IntArray(4) { habitIndex ->
        history.count { record ->
            record.date.year == displayYear && record.completedHabits.contains(habitIndex)
        }
    }

    var horizontalDragAmount by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (horizontalDragAmount > 80f) {
                            yearOffset--
                        } else if (horizontalDragAmount < -80f && yearOffset < 0) {
                            yearOffset++
                        }
                        horizontalDragAmount = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        horizontalDragAmount += dragAmount
                    }
                )
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "<",
                color = Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { yearOffset-- }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (yearOffset == 0) "THIS YEAR" else displayYear.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (yearOffset < 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ">",
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { yearOffset++ }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            yearCounts.forEachIndexed { index, count ->
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = count.toString(),
                        color = habits[index].color,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = habitNames[index],
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ConsistencyGraph(historyData: List<DayRecord>) {
    val windowSize = 7
    val maxPoints = 22
    val rangeEnd = (historyData.size - (windowSize - 1)).coerceAtMost(maxPoints)
    val points = (0 until rangeEnd).map { i ->
        historyData.subList(i, i + windowSize).count { it.completedHabits.isNotEmpty() }.toFloat() / windowSize
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        // Y-axis labels
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Text(text = "100", color = Color.Gray, fontSize = 10.sp)
            Text(text = "50", color = Color.Gray, fontSize = 10.sp)
            Text(text = "0", color = Color.Gray, fontSize = 10.sp)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            // Inset the drawing range so the top/bottom grid lines align with the
            // vertical centres of the "100" and "0" text labels (which SpaceBetween
            // places ~half a line-height away from the column edges).
            val vPad = 6.dp.toPx()
            val graphTop = vPad
            val graphBottom = height - vPad
            val graphHeight = graphBottom - graphTop
            val spacing = if (points.size > 1) width / (points.size - 1) else 0f

            val lines = 4
            for (i in 0..lines) {
                val y = graphBottom - (i * graphHeight / lines)
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (points.size < 2) return@Canvas

            val path = Path()
            val gradientPath = Path()

            val firstX = 0f
            val firstY = graphBottom - (points[0] * graphHeight)
            path.moveTo(firstX, firstY)
            gradientPath.moveTo(firstX, graphBottom)
            gradientPath.lineTo(firstX, firstY)

            for (i in 0 until points.size - 1) {
                val x1 = i * spacing
                val y1 = graphBottom - (points[i] * graphHeight)
                val x2 = (i + 1) * spacing
                val y2 = graphBottom - (points[i + 1] * graphHeight)

                val controlX1 = x1 + spacing / 2
                val controlX2 = x2 - spacing / 2

                path.cubicTo(controlX1, y1, controlX2, y2, x2, y2)
                gradientPath.cubicTo(controlX1, y1, controlX2, y2, x2, y2)
            }

            gradientPath.lineTo(width, graphBottom)
            gradientPath.close()

            val green = Color(0xFF33B679)
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(green, green.copy(alpha = 0.2f)),
                    startY = graphTop,
                    endY = graphBottom
                ),
                style = Stroke(width = 2.dp.toPx())
            )

            drawPath(
                path = gradientPath,
                brush = Brush.verticalGradient(
                    colors = listOf(green.copy(alpha = 0.2f), Color.Transparent),
                    startY = graphTop,
                    endY = graphBottom
                )
            )
        }
    }
}

@Composable
fun QuadrantCircle(completedHabits: Set<Int>, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        // Use strictly radius from size to ensure uniform growth
        val radius = size.toPx() / 2f
        
        // Mask with black background circle
        drawCircle(color = Color.Black, radius = radius)
        
        // Background translucent circle
        drawCircle(color = Color.White.copy(alpha = 0.1f), radius = radius)

        habits.forEachIndexed { index, habit ->
            if (completedHabits.contains(index)) {
                val startAngles = listOf(180f, 270f, 90f, 0f)
                drawArc(
                    color = habit.color,
                    startAngle = startAngles[index],
                    sweepAngle = 90f,
                    useCenter = true,
                    style = Fill
                )
            }
        }

        // If empty, draw the opaque black slash
        if (completedHabits.isEmpty()) {
            val strokeWidthPx = size.toPx() * 1.5f / 16f
            val offset = radius * 0.7071f
            drawLine(
                color = Color.Black,
                start = Offset(center.x + offset, center.y - offset),
                end = Offset(center.x - offset, center.y + offset),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        }
    }
}

fun formatCurrentDate(dateTime: LocalDateTime): String {
    val day = dateTime.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "${dateTime.format(DateTimeFormatter.ofPattern("MMMM"))} ${day}${suffix}, ${dateTime.year}"
}

@Composable
fun HabitGrid(
    completedHabits: Set<Int>,
    habitNames: List<String>,
    onToggleHabit: (Int) -> Unit,
    onRenameHabit: (Int, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().height(240.dp)) {
        Row(modifier = Modifier.weight(1f)) {
            HabitButton(0, habits[0], habitNames[0], completedHabits.contains(0), onToggleHabit, onRenameHabit, Modifier.weight(1f))
            HabitButton(1, habits[1], habitNames[1], completedHabits.contains(1), onToggleHabit, onRenameHabit, Modifier.weight(1f))
        }
        Row(modifier = Modifier.weight(1f)) {
            HabitButton(2, habits[2], habitNames[2], completedHabits.contains(2), onToggleHabit, onRenameHabit, Modifier.weight(1f))
            HabitButton(3, habits[3], habitNames[3], completedHabits.contains(3), onToggleHabit, onRenameHabit, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitButton(
    index: Int,
    habit: Habit,
    name: String,
    isCompleted: Boolean,
    onToggle: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(name) { mutableStateOf(TextFieldValue(text = name, selection = TextRange(name.length))) }
    val focusRequester = remember { FocusRequester() }
    var hasBeenFocused by remember { mutableStateOf(false) }

    fun commitEdit() {
        if (!isEditing) return
        val trimmed = editText.text.trim()
        if (trimmed.isNotEmpty()) {
            onRename(index, trimmed)
        } else {
            editText = TextFieldValue(text = name, selection = TextRange(name.length))
        }
        isEditing = false
        hasBeenFocused = false
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible && isEditing) commitEdit()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(habit.color.copy(alpha = if (isCompleted) 1f else 0.35f))
            .combinedClickable(
                onClick = { 
                    if (isEditing) {
                        commitEdit()
                    } else {
                        onToggle(index)
                    }
                },
                onLongClick = {
                    editText = TextFieldValue(text = name, selection = TextRange(name.length))
                    isEditing = true
                }
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isEditing) {
            BasicTextField(
                value = editText,
                onValueChange = { editText = it },
                textStyle = TextStyle(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hasBeenFocused = true
                        } else if (hasBeenFocused) {
                            commitEdit()
                        }
                    }
                    .onKeyEvent {
                        if (it.key == Key.Enter) {
                            commitEdit()
                            true
                        } else false
                    }
            )
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        } else {
            Text(
                text = name,
                color = Color.White.copy(alpha = if (isCompleted) 1f else 0.4f),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    NoZeroDaysTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            NoZeroDaysApp()
        }
    }
}
