package com.example.ocrmedicationapp

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// -----------------------------------------------------------------------------
// 1. UI Data Model
// -----------------------------------------------------------------------------
data class MedicationItemUi(
    val id: String,
    val name: String,
    val timeSlot: String,
    val quantity: String,
    val frequency: String,
    val isTaken: Boolean = false
)

// -----------------------------------------------------------------------------
// 2. Dynamic ReorderState Manager (Optimized for 60/120Hz Hardware Acceleration)
// -----------------------------------------------------------------------------
class ReorderState<T>(
    private val items: SnapshotStateList<T>
) {
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    var dragOffsetY by mutableFloatStateOf(0f)
        private set

    val itemHeights = mutableStateMapOf<Int, Int>()

    fun onDragStart(index: Int) {
        draggedIndex = index
        dragOffsetY = 0f
    }

    fun onDrag(dragAmount: Float) {
        val currentIndex = draggedIndex ?: return
        dragOffsetY += dragAmount

        val currentItemHeight = itemHeights[currentIndex] ?: 180

        if (dragOffsetY > currentItemHeight / 2f && currentIndex < items.lastIndex) {
            val moved = items.removeAt(currentIndex)
            items.add(currentIndex + 1, moved)
            draggedIndex = currentIndex + 1
            dragOffsetY -= currentItemHeight
        } else if (dragOffsetY < -currentItemHeight / 2f && currentIndex > 0) {
            val targetHeight = itemHeights[currentIndex - 1] ?: currentItemHeight
            val moved = items.removeAt(currentIndex)
            items.add(currentIndex - 1, moved)
            draggedIndex = currentIndex - 1
            dragOffsetY += targetHeight
        }
    }

    fun onDragEnd() {
        draggedIndex = null
        dragOffsetY = 0f
    }

    fun updateItemHeight(index: Int, height: Int) {
        if (height > 0 && itemHeights[index] != height) {
            itemHeights[index] = height
        }
    }
}

@Composable
fun <T> rememberReorderState(items: SnapshotStateList<T>): ReorderState<T> {
    return remember(items) { ReorderState(items) }
}

// -----------------------------------------------------------------------------
// 3. Embedded Section Composable for ViewPager Home Layout
// -----------------------------------------------------------------------------
@Composable
fun HomeMedicationComposeSection(
    items: List<MedicationItemUi>,
    onSaveOrder: (List<MedicationItemUi>) -> Unit = {},
    onToggleTaken: (String) -> Unit = {},
    onDeleteMeds: (List<String>) -> Unit = {},
    onEditMed: (String) -> Unit = {},
    onEditModeChanged: (Boolean) -> Unit = {}
) {
    val medications = remember { mutableStateListOf<MedicationItemUi>() }
    var isEditMode by remember { mutableStateOf(false) }

    LaunchedEffect(items, isEditMode) {
        if (!isEditMode && items.isNotEmpty()) {
            if (medications.map { it.id } != items.map { it.id } ||
                medications.map { it.isTaken } != items.map { it.isTaken }
            ) {
                medications.clear()
                medications.addAll(items)
            }
        }
    }

    val selectedIds = remember { mutableStateListOf<String>() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val currentView = LocalView.current
    LaunchedEffect(isEditMode) {
        // Post onEditModeChanged to the next frame to prevent frame collision with Compose layout pass
        currentView.post {
            onEditModeChanged(isEditMode)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = isEditMode,
            enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(180))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedIds.size} Selected",
                    color = colorResource(R.color.text_primary),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasSelection = selectedIds.isNotEmpty()
                    val trashAlpha by animateFloatAsState(
                        targetValue = if (hasSelection) 1.0f else 0.4f,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "trashAlpha"
                    )

                    FloatingActionButton(
                        onClick = { if (hasSelection) showDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                        modifier = Modifier
                            .size(40.dp)
                            .alpha(trashAlpha),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                    }

                    FloatingActionButton(
                        onClick = {
                            val currentOrder = medications.toList()
                            isEditMode = false
                            // Defer DB persistence & data updates until after 240ms nav bar animation completes
                            currentView.postDelayed({
                                onSaveOrder(currentOrder)
                                selectedIds.clear()
                            }, 300)
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Done", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        MedicationReorderList(
            medications = medications,
            isEditMode = isEditMode,
            selectedIds = selectedIds,
            shakeAngle = 0f,
            onLongPressItem = { isEditMode = true },
            onToggleSelect = { id ->
                if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
            },
            onToggleTaken = { medId ->
                val index = medications.indexOfFirst { it.id == medId }
                if (index >= 0) {
                    val current = medications[index]
                    medications[index] = current.copy(isTaken = !current.isTaken)
                }
                onToggleTaken(medId)
            },
            onEditMed = onEditMed,
            onDeleteMed = { medId ->
                medications.removeAll { it.id == medId }
                onDeleteMeds(listOf(medId))
            }
        )

        if (showDeleteDialog) {
            BatchDeleteConfirmationDialog(
                selectedCount = selectedIds.size,
                onDismiss = { showDeleteDialog = false },
                onConfirmDelete = {
                    onDeleteMeds(selectedIds.toList())
                    medications.removeAll { selectedIds.contains(it.id) }
                    selectedIds.clear()
                    isEditMode = false
                    showDeleteDialog = false
                }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 3b. Standalone Dashboard Composable Implementation
// -----------------------------------------------------------------------------
@Composable
fun MedicationDashboardScreen(
    initialMedications: List<MedicationItemUi> = sampleMedications(),
    onAddManualClick: () -> Unit = {},
    onSaveOrder: (List<MedicationItemUi>) -> Unit = {},
    onEditModeChanged: (Boolean) -> Unit = {}
) {
    val medications = remember { mutableStateListOf(*initialMedications.toTypedArray()) }
    var isEditMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val currentView = LocalView.current
    LaunchedEffect(isEditMode) {
        currentView.post {
            onEditModeChanged(isEditMode)
        }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !isEditMode,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(animationSpec = tween(150))
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(Icons.Default.DateRange, contentDescription = "History") },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        label = { Text("Search") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        },
        floatingActionButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isEditMode) {
                    FloatingActionButton(
                        onClick = onAddManualClick,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Medication")
                    }
                } else {
                    val hasSelection = selectedIds.isNotEmpty()
                    val trashAlpha by animateFloatAsState(
                        targetValue = if (hasSelection) 1.0f else 0.4f,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "trashAlpha"
                    )

                    FloatingActionButton(
                        onClick = { if (hasSelection) showDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = if (hasSelection) 6.dp else 0.dp
                        ),
                        modifier = Modifier.alpha(trashAlpha)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                    }

                    FloatingActionButton(
                        onClick = {
                            val currentOrder = medications.toList()
                            isEditMode = false
                            currentView.postDelayed({
                                onSaveOrder(currentOrder)
                                selectedIds.clear()
                            }, 300)
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save and Done")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MedicationReorderList(
                medications = medications,
                isEditMode = isEditMode,
                selectedIds = selectedIds,
                shakeAngle = 0f,
                onLongPressItem = { if (!isEditMode) isEditMode = true },
                onToggleSelect = { id ->
                    if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                }
            )

            if (showDeleteDialog) {
                BatchDeleteConfirmationDialog(
                    selectedCount = selectedIds.size,
                    onDismiss = { showDeleteDialog = false },
                    onConfirmDelete = {
                        medications.removeAll { selectedIds.contains(it.id) }
                        selectedIds.clear()
                        isEditMode = false
                        showDeleteDialog = false
                    }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. REORDERABLE LIST COMPOSABLE WITH REORDERSTATE MANAGER
// -----------------------------------------------------------------------------
@Composable
fun MedicationReorderList(
    medications: SnapshotStateList<MedicationItemUi>,
    isEditMode: Boolean,
    selectedIds: List<String>,
    shakeAngle: Float = 0f,
    onLongPressItem: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleTaken: (String) -> Unit = {},
    onEditMed: (String) -> Unit = {},
    onDeleteMed: (String) -> Unit = {}
) {
    val reorderState = rememberReorderState(medications)

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        medications.forEachIndexed { index, item ->
            key(item.id) {
                val isBeingDragged = index == reorderState.draggedIndex

                MedicationCardItem(
                    item = item,
                    index = index,
                    reorderState = reorderState,
                    isEditMode = isEditMode,
                    isSelected = selectedIds.contains(item.id),
                    shakeAngle = if (isEditMode && !isBeingDragged) shakeAngle else 0f,
                    offsetY = { if (index == reorderState.draggedIndex) reorderState.dragOffsetY else 0f },
                    isBeingDragged = isBeingDragged,
                    onLongPress = onLongPressItem,
                    onToggleSelect = { onToggleSelect(item.id) },
                    onToggleTaken = { onToggleTaken(item.id) },
                    onEditMed = { onEditMed(item.id) },
                    onDeleteMed = { onDeleteMed(item.id) }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. CARD ITEM WITH DRAW-PHASE DEFERRED GRAPHICSLAYER RENDERING (ZERO LAG)
// -----------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun MedicationCardItem(
    item: MedicationItemUi,
    index: Int,
    reorderState: ReorderState<MedicationItemUi>,
    isEditMode: Boolean,
    isSelected: Boolean,
    shakeAngle: Float = 0f,
    offsetY: () -> Float,
    isBeingDragged: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleTaken: (String) -> Unit = {},
    onEditMed: (String) -> Unit = {},
    onDeleteMed: (String) -> Unit = {}
) {
    val currentView = LocalView.current
    var showMenu by remember { mutableStateOf(false) }

    // Stable lambda callbacks to prevent recomposition invalidation during drag
    val currentOnToggleSelect by rememberUpdatedState(onToggleSelect)
    val currentOnToggleTaken by rememberUpdatedState(onToggleTaken)
    val currentOnEditMed by rememberUpdatedState(onEditMed)
    val currentOnDeleteMed by rememberUpdatedState(onDeleteMed)

    val defaultCardBg = colorResource(R.color.grid_card_bg)
    val selectedCardBg = colorResource(R.color.selected_option_bg)
    val defaultCardBorder = colorResource(R.color.glass_stroke)
    val tealAccent = colorResource(R.color.accent_teal)
    val textPrimary = colorResource(R.color.text_primary)
    val textSecondary = colorResource(R.color.text_secondary)
    val statusTakenBg = colorResource(R.color.status_pill_taken)
    val statusTakenText = colorResource(R.color.status_pill_taken_text)
    val statusPendingBg = colorResource(R.color.status_pill_pending)
    val statusPendingText = colorResource(R.color.status_pill_pending_text)

    // Hardware accelerated GPU color & scale transitions
    val cardScale by animateFloatAsState(
        targetValue = when {
            isBeingDragged -> 1.025f
            isSelected -> 0.985f
            else -> 1.0f
        },
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        ),
        label = "cardScale"
    )

    val cardBgColor by animateColorAsState(
        targetValue = when {
            isBeingDragged -> selectedCardBg
            isSelected -> selectedCardBg
            else -> defaultCardBg
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "cardBgColor"
    )

    val cardBorderColor by animateColorAsState(
        targetValue = when {
            isBeingDragged -> tealAccent
            isSelected -> tealAccent
            else -> defaultCardBorder
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "cardBorderColor"
    )

    val checkIconScale by animateFloatAsState(
        targetValue = if (item.isTaken) 1.15f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "checkIconScale"
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isBeingDragged) 24.dp else if (isSelected) 6.dp else 2.dp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isBeingDragged) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                val h = coordinates.size.height
                if (h > 0 && !isBeingDragged) {
                    reorderState.updateItemHeight(index, h)
                }
            }
            .graphicsLayer {
                // Defer translationY, scale, rotation to DRAW PHASE on GPU
                translationY = offsetY()
                scaleX = cardScale
                scaleY = cardScale
                rotationZ = shakeAngle
                shadowElevation = if (isBeingDragged) 24f else if (isSelected) 6f else 0f
            }
            .combinedClickable(
                onClick = { if (isEditMode) currentOnToggleSelect() },
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag Handle Icon (Visible in Edit Mode)
            AnimatedVisibility(
                visible = isEditMode,
                enter = fadeIn(animationSpec = tween(180)) + expandHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing))
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Reorder Drag Handle",
                    tint = if (isBeingDragged) tealAccent else textSecondary,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(32.dp)
                        .pointerInteropFilter { motionEvent ->
                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    currentView.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    currentView.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                        .pointerInput(item.id) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    currentView.parent?.requestDisallowInterceptTouchEvent(true)
                                    reorderState.onDragStart(index)
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    reorderState.onDrag(dragAmount)
                                },
                                onDragEnd = {
                                    currentView.parent?.requestDisallowInterceptTouchEvent(false)
                                    reorderState.onDragEnd()
                                },
                                onDragCancel = {
                                    currentView.parent?.requestDisallowInterceptTouchEvent(false)
                                    reorderState.onDragEnd()
                                }
                            )
                        }
                )
            }

            // Name & Schedule
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (!isEditMode) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (item.isTaken) statusTakenBg else statusPendingBg
                        ) {
                            Text(
                                text = if (item.isTaken) "TAKEN" else "PENDING",
                                color = if (item.isTaken) statusTakenText else statusPendingText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = !isEditMode,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Qty: ${item.quantity} • ${item.frequency}",
                            color = textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.timeSlot,
                    color = tealAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Controls on far right
            AnimatedVisibility(
                visible = !isEditMode,
                enter = fadeIn(animationSpec = tween(180)) + expandHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentOnToggleTaken(item.id) }) {
                        Icon(
                            imageVector = if (item.isTaken) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Toggle Taken",
                            tint = if (item.isTaken) tealAccent else textSecondary,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = checkIconScale
                                    scaleY = checkIconScale
                                }
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Medication Options",
                                tint = textSecondary
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(defaultCardBg)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Edit Medication",
                                        color = textPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = tealAccent
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    currentOnEditMed(item.id)
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Delete Medication",
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    currentOnDeleteMed(item.id)
                                }
                            )
                        }
                    }
                }
            }
            // Checkbox for selection
            AnimatedVisibility(
                visible = isEditMode,
                enter = fadeIn(animationSpec = tween(180)) + expandHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing))
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { currentOnToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = tealAccent,
                        uncheckedColor = textSecondary
                    )
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 6. CONFIRMATION DIALOG
// -----------------------------------------------------------------------------
@Composable
fun BatchDeleteConfirmationDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirmDelete) {
                Text("Confirm", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        title = { Text("Delete Selected") },
        text = {
            Text("Are you sure you want to delete $selectedCount selected medication(s)?")
        },
        containerColor = Color(0xFF192231),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

// -----------------------------------------------------------------------------
// SAMPLE DATA
// -----------------------------------------------------------------------------
private fun sampleMedications(): List<MedicationItemUi> {
    return listOf(
        MedicationItemUi("1", "Amoxicillin", "08:00 AM", "1", "1 time a day", isTaken = true),
        MedicationItemUi("2", "Metformin", "12:00 PM", "2", "2 times a day", isTaken = false),
        MedicationItemUi("3", "Ibuprofen", "06:00 PM", "1", "As needed", isTaken = false)
    )
}
