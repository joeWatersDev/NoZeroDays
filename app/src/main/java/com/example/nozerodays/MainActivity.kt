package com.example.nozerodays

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

// --- Persistence Layer ---

@Entity(tableName = "day_records")
data class DayRecord(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val date: LocalDateTime,
    val completedHabits: Set<Int>
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

    @Update
    suspend fun update(record: DayRecord)

    @Query("SELECT * FROM day_records WHERE date >= :start AND date < :end LIMIT 1")
    suspend fun getRecordForRange(start: LocalDateTime, end: LocalDateTime): DayRecord?

    @Query("SELECT * FROM day_records WHERE date >= :since ORDER BY date ASC")
    fun getRecent(since: LocalDateTime): Flow<List<DayRecord>>
}

@Database(entities = [DayRecord::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayRecordDao(): DayRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "no_zero_days_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class HabitViewModel(applicationContext: Context) : ViewModel() {
    private val db = AppDatabase.getDatabase(applicationContext)
    private val dao = db.dayRecordDao()

    val history: StateFlow<List<DayRecord>> = dao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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

    suspend fun ensureTodayExists() {
        withContext(Dispatchers.IO) {
            val startOfDay = LocalDate.now().atStartOfDay()
            val endOfDay = startOfDay.plusDays(1)
            
            val existing = dao.getRecordForRange(startOfDay, endOfDay)
            if (existing == null) {
                dao.insert(DayRecord(date = LocalDateTime.now(), completedHabits = emptySet()))
            }
        }
    }
}

// --- UI Layer ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    Habit("created", Color(0xFFEB5353)), // TL
    Habit("helped", Color(0xFFD5BA26)),  // TR
    Habit("learned", Color(0xFF36AE7C)), // BL
    Habit("health", Color(0xFF187498))   // BR
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NoZeroDaysApp() {
    val context = LocalContext.current
    val viewModel: HabitViewModel = viewModel { HabitViewModel(context.applicationContext) }
    val history by viewModel.history.collectAsState()
    
    var timeRemaining by remember { mutableStateOf("00:00:00") }
    var habitNames by remember { mutableStateOf(habits.map { it.name }) }
    var showStats by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // Ensure we have at least one day to show (today)
    LaunchedEffect(Unit) {
        viewModel.ensureTodayExists()
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // ── 1. Header Section (Top Left) ──
            Column(modifier = Modifier.padding(start = 32.dp, top = 64.dp)) {
                Text(
                    text = activeDay.date.format(DateTimeFormatter.ofPattern("EEEE")),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 64.sp,
                    lineHeight = 64.sp
                )
                Text(
                    text = formatCurrentDate(activeDay.date),
                    color = Color(0xFFC3C3C3),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "$timeRemaining still remaining",
                    color = Color(0xFFC3C3C3),
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.weight(1.5f))

            // ── 2. Tagline & Stats Button ──
            Column(modifier = Modifier.padding(start = 32.dp)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color(0xFFC3C3C3))) {
                            append("Make every day ")
                        }
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                            append("count")
                        }
                    },
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        .clickable { showStats = true }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Stats",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── 3. Horizontal Timeline ──
            // Each page slot is a fixed 72dp (inactiveSize 36 + gap 36).
            // Circle sizes are interpolated visually via lerp and overflow the slot
            // using wrapContentSize(unbounded = true).
            //
            // Position the active circle so its RIGHT edge is 36dp from the screen edge.
            // Active circle center X = screenWidth - 36 - activeSize/2 = screenWidth - 88.5dp
            // Page slot center at startPadding + pageSize/2, so:
            //   startPadding = activeCenterX - pageSize/2
            run {
                val activeSize = 105.dp
                val inactiveSize = 36.dp
                val gap = 36.dp
                val pageSize = (activeSize / 2) + gap + (inactiveSize / 2) // 106.5dp
                val activeCenterX = screenWidth - 36.dp - (activeSize / 2)
                val startPadding = activeCenterX - (pageSize / 2)
                val endPadding = (screenWidth - startPadding - pageSize).coerceAtLeast(0.dp)

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
                            pagerSnapDistance = PagerSnapDistance.atMost(10)
                        ),
                        key = { history[it].id },
                        verticalAlignment = Alignment.CenterVertically
                    ) { pageIndex ->
                        val pageOffset = kotlin.math.abs(
                            pageIndex - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        ).coerceIn(0f, 1f)

                        val circleSize = lerp(activeSize, inactiveSize, pageOffset)

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(unbounded = true),
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

            Spacer(modifier = Modifier.height(36.dp))

            // ── 4. Habit Grid (Fixed at Bottom) ──
            HabitGrid(
                completedHabits = activeDay.completedHabits,
                habitNames = habitNames,
                onToggleHabit = { habitIndex ->
                    viewModel.toggleHabit(activeDay, habitIndex)
                },
                onRenameHabit = { index, newName ->
                    habitNames = habitNames.toMutableList().also { it[index] = newName }
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
    val last30Days = history.takeLast(30)
    
    val totals = IntArray(4) { habitIndex ->
        history.count { it.completedHabits.contains(habitIndex) }
    }

    var verticalDragAmount by remember { mutableStateOf(0f) }

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
                    text = "LAST 30 DAYS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            repeat(10) { colIndex ->
                                val recordIndex = rowIndex * 10 + colIndex
                                if (recordIndex < last30Days.size) {
                                    QuadrantCircle(
                                        completedHabits = last30Days[recordIndex].completedHabits,
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
                    text = "LIFE LINE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                LifeLineGraph(history.takeLast(39))
            }

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

            ByDaySection(history, habitNames)
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ByDaySection(history: List<DayRecord>, habitNames: List<String>) {
    val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val habitDayCounts = Array(4) { IntArray(7) }

    history.forEach { record ->
        val dayIndex = record.date.dayOfWeek.value - 1
        record.completedHabits.forEach { habitIndex ->
            if (habitIndex in 0..3) {
                habitDayCounts[habitIndex][dayIndex]++
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "BY DAY",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Spacer(modifier = Modifier.width(60.dp))
            daysOfWeek.forEach { day ->
                Text(
                    text = day,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        habitNames.forEachIndexed { habitIndex, name ->
            val habitColor = habits[habitIndex].color
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(habitColor.copy(alpha = 0.1f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    color = habitColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(60.dp)
                )
                
                repeat(7) { dayIndex ->
                    Text(
                        text = habitDayCounts[habitIndex][dayIndex].toString(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun LifeLineGraph(historyData: List<DayRecord>) {
    val points = mutableListOf<Float>()
    val windowSize = 10
    val maxPoints = 30
    
    for (i in 0 until (historyData.size - (windowSize - 1)).coerceAtMost(maxPoints)) {
        val window = historyData.subList(i, i + windowSize)
        val average = window.count { it.completedHabits.isNotEmpty() }.toFloat() / windowSize.toFloat()
        points.add(average)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val spacing = if (points.size > 1) width / (points.size - 1) else 0f

            val lines = 4
            for (i in 0..lines) {
                val y = height - (i * height / lines)
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
            val firstY = height - (points[0] * height)
            path.moveTo(firstX, firstY)
            gradientPath.moveTo(firstX, height)
            gradientPath.lineTo(firstX, firstY)

            for (i in 0 until points.size - 1) {
                val x1 = i * spacing
                val y1 = height - (points[i] * height)
                val x2 = (i + 1) * spacing
                val y2 = height - (points[i+1] * height)
                
                val controlX1 = x1 + spacing / 2
                val controlX2 = x2 - spacing / 2
                
                path.cubicTo(controlX1, y1, controlX2, y2, x2, y2)
                gradientPath.cubicTo(controlX1, y1, controlX2, y2, x2, y2)
            }
            
            gradientPath.lineTo(width, height)
            gradientPath.close()

            val green = Color(0xFF33B679)
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(green, green.copy(alpha = 0.2f)),
                    startY = 0f,
                    endY = height
                ),
                style = Stroke(width = 2.dp.toPx())
            )

            drawPath(
                path = gradientPath,
                brush = Brush.verticalGradient(
                    colors = listOf(green.copy(alpha = 0.2f), Color.Transparent),
                    startY = 0f,
                    endY = height
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
            val strokeWidthPx = (size.toPx() / 16.dp.toPx()) * 1.5.dp.toPx()
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
    var editText by remember(name) { mutableStateOf(name) }
    val focusRequester = remember { FocusRequester() }
    var hasBeenFocused by remember { mutableStateOf(false) }

    fun commitEdit() {
        if (!isEditing) return
        val trimmed = editText.trim()
        if (trimmed.isNotEmpty()) {
            onRename(index, trimmed)
        } else {
            editText = name
        }
        isEditing = false
        hasBeenFocused = false
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
                onLongClick = { isEditing = true }
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
