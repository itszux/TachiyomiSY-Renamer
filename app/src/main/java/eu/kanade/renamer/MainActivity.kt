package eu.kanade.renamer

import eu.kanade.renamer.R
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class InternalManga(
    val title: String,
    val chapters: List<InternalChapter>
)

data class InternalChapter(
    val url: String,
    val name: String,
    val scanlator: String?,
    val chapterNumber: Double
)

data class GroupedManga(
    val sourceName: String,
    val mangaName: String,
    val absolutePath: String
)

data class GroupedSource(
    val sourceName: String,
    val mangas: List<GroupedManga>
)

data class RenameOption(
    val newName: String,
    val newPath: String,
    val scanlator: String,
    val chapterName: String,
    val selected: Boolean = false,
    val lackingHashOnly: Boolean = false
)

data class FolderRenameGroup(
    val mangaTitle: String,
    val oldName: String,
    val oldPath: String,
    val options: List<RenameOption>
)

enum class ScreenStep {
    CONFIGURATION,
    TREE_SELECTION,
    SCAN_RESULTS,
    ABOUT
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE040FB),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var hasPermission by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var downloadsPath by remember { mutableStateOf("") }
    var currentStep by remember { mutableStateOf(ScreenStep.CONFIGURATION) }
    
    BackHandler(enabled = currentStep != ScreenStep.CONFIGURATION) {
        when (currentStep) {
            ScreenStep.TREE_SELECTION -> currentStep = ScreenStep.CONFIGURATION
            ScreenStep.SCAN_RESULTS -> currentStep = ScreenStep.TREE_SELECTION
            ScreenStep.ABOUT -> currentStep = ScreenStep.CONFIGURATION
            else -> {}
        }
    }
    
    var groupedSources by remember { mutableStateOf<List<GroupedSource>>(emptyList()) }
    var selectedMangaPaths by remember { mutableStateOf(emptySet<String>()) }
    var collapsedSourceNames by remember { mutableStateOf(emptySet<String>()) }
    var isLoadingStructure by remember { mutableStateOf(false) }
    
    var scanResults by remember { mutableStateOf<List<FolderRenameGroup>>(emptyList()) }
    var allScanResults by remember { mutableStateOf<List<FolderRenameGroup>>(emptyList()) }
    var mangaRenameCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var isScanning by remember { mutableStateOf(false) }
    var isRenaming by remember { mutableStateOf(false) }
    var showCompletionDialog by remember { mutableStateOf(false) }
    var showWelcomeDialog by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLackingHashOnly by remember { mutableStateOf(false) }
    var scanlatorDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBulkScanlator by remember { mutableStateOf("") }
    
    LaunchedEffect(scanResults) {
        if (scanResults.isEmpty()) {
            selectedBulkScanlator = ""
        }
    }
    
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    
    fun addLog(msg: String) {
        scope.launch(Dispatchers.Main) {
            logs = logs + msg
        }
    }
    
    fun checkPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        hasPermission = granted
        showPermissionDialog = !granted
    }
    
    LaunchedEffect(Unit) {
        checkPermission()
    }
    
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermission()
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedUri = it
            selectedFileName = it.path?.substringAfterLast("/") ?: "selected_backup"
            addLog("Selected backup file: $selectedFileName")
        }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = getPathFromUri(context, it)
            if (path != null) {
                downloadsPath = path
                addLog("Selected downloads folder: $path")
            } else {
                Toast.makeText(context, "Could not resolve physical directory path", Toast.LENGTH_SHORT).show()
                addLog("Error: Could not resolve directory path from URI")
            }
        }
    }

    // Toggle option inside the group, deselecting other options in the same group
    fun toggleOption(
        results: List<FolderRenameGroup>,
        targetGroup: FolderRenameGroup,
        targetOption: RenameOption
    ): List<FolderRenameGroup> {
        return results.map { group ->
            if (group.oldPath == targetGroup.oldPath) {
                val newOptions = group.options.map { option ->
                    if (option.newName == targetOption.newName) {
                        option.copy(selected = !option.selected)
                    } else {
                        option.copy(selected = false)
                    }
                }
                group.copy(options = newOptions)
            } else {
                group
            }
        }
    }

    // Permission Alert Dialog Prompt
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Storage Permission Required") },
            text = { Text("This helper app requires 'All Files Access' permission to scan and rename Tachiyomi download folders on your device storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            requestPermissionLauncher.launch(intent)
                        }
                    }
                ) {
                    Text("Grant Permission")
                }
            }
        )
    }

    if (showWelcomeDialog) {
        AlertDialog(
            onDismissRequest = { showWelcomeDialog = false },
            title = { Text("Welcome & How to Use", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This app renames old chapter download folders so they are recognized by Tachiyomi when source extensions update their URLs or hashing schemes.",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Text("Steps to use:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("1. Create a backup inside Tachiyomi/TachiyomiSY (More ➔ Settings ➔ Backup and restore ➔ Create backup).", fontSize = 12.sp, color = Color.LightGray)
                    Text("2. Click 'Select Backup File' and select that backup file.", fontSize = 12.sp, color = Color.LightGray)
                    Text("3. Enter or Browse for your downloads directory (e.g. 'TachiyomiSY/downloads').", fontSize = 12.sp, color = Color.LightGray)
                    Text("4. Click 'Load Directory Structure' and check which mangas to scan.", fontSize = 12.sp, color = Color.LightGray)
                    Text("5. Click 'Scan', select the correct scanlators/chapters, and click 'Apply Renames'.", fontSize = 12.sp, color = Color.LightGray)
                    Text("6. Open TachiyomiSY: Go to More ➔ Settings ➔ Advanced ➔ Reindex downloads.", fontSize = 12.sp, color = Color.LightGray)
                }
            },
            confirmButton = {
                Button(onClick = { showWelcomeDialog = false }) {
                    Text("Get Started")
                }
            }
        )
    }

    if (showCompletionDialog) {
        AlertDialog(
            onDismissRequest = { showCompletionDialog = false },
            title = { Text("Renaming Complete!", fontWeight = FontWeight.Bold) },
            text = { Text("Now go to TachiyomiSY:\nMore tab ➔ Settings ➔ Advanced ➔ Reindex downloads") },
            confirmButton = {
                Button(
                    onClick = { showCompletionDialog = false }
                ) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TachiyomiSY Renamer", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                actions = {
                    IconButton(onClick = { currentStep = ScreenStep.ABOUT }) {
                        Icon(Icons.Default.Info, contentDescription = "About", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Permission Card
            if (!hasPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF331E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.Red)
                            Text("All Files Access Required", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text("This helper app needs permission to access your storage so it can list and rename chapter folders in public directories.", color = Color.LightGray, fontSize = 14.sp)
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    requestPermissionLauncher.launch(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            when (currentStep) {
                ScreenStep.CONFIGURATION -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Step 1: Configuration", fontWeight = FontWeight.Bold, color = Color.White)
                            
                            // Backup Selector
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030))
                                ) {
                                    Text("Select Backup File")
                                }
                                Text(
                                    text = if (selectedUri != null) selectedFileName else "No backup selected",
                                    color = if (selectedUri != null) Color.Green else Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Directory input with Browse Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = downloadsPath,
                                    onValueChange = { downloadsPath = it },
                                    label = { Text("Downloads Directory") },
                                    placeholder = { Text("e.g. TachiyomiSY/downloads", color = Color.Gray) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedTextColor = Color.White,
                                        focusedTextColor = Color.White
                                    )
                                )
                                Button(
                                    onClick = { dirPickerLauncher.launch(null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030))
                                ) {
                                    Text("Browse")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (downloadsPath.isBlank()) {
                                Toast.makeText(context, "Please select a downloads directory first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (selectedUri == null) {
                                Toast.makeText(context, "Please select a backup file first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (!hasPermission) {
                                Toast.makeText(context, "Please grant All Files Access permission first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isLoadingStructure = true
                            errorMessage = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val structures = loadSourcesAndMangas(downloadsPath)
                                    
                                    val inputStream = context.contentResolver.openInputStream(selectedUri!!) ?: throw Exception("Could not open file")
                                    val fileBytes = inputStream.readBytes()
                                    val mangaList = parseBackup(fileBytes) { }
                                    
                                    val allMangas = structures.flatMap { it.mangas }
                                    val results = scanFolders(mangaList, allMangas) { }
                                    
                                    val counts = results.filter { group ->
                                        group.options.any { !it.lackingHashOnly }
                                    }.groupBy {
                                        File(it.oldPath).parentFile?.absolutePath ?: ""
                                    }.mapValues { (_, groups) -> groups.size }
                                    
                                    withContext(Dispatchers.Main) {
                                        groupedSources = structures
                                        allScanResults = results
                                        mangaRenameCounts = counts
                                        selectedMangaPaths = emptySet()
                                        isLoadingStructure = false
                                        currentStep = ScreenStep.TREE_SELECTION
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        errorMessage = e.message ?: "Unknown error"
                                        isLoadingStructure = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoadingStructure,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isLoadingStructure) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading and scanning directories...")
                        } else {
                            Text("Load & Scan Directory")
                        }
                    }

                    // Error Display
                    AnimatedVisibility(visible = errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF331E1E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(errorMessage ?: "", modifier = Modifier.padding(12.dp), color = Color.Red)
                        }
                    }
                }

                ScreenStep.TREE_SELECTION -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Step 2: Selection", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            TextButton(onClick = { currentStep = ScreenStep.CONFIGURATION }) {
                                Text("Back to Config", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val allPaths = groupedSources.flatMap { it.mangas }.map { it.absolutePath }.toSet()
                                    selectedMangaPaths = allPaths
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Select All", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    val pathsWithChanges = groupedSources.flatMap { it.mangas }
                                        .map { it.absolutePath }
                                        .filter { (mangaRenameCounts[it] ?: 0) > 0 }
                                        .toSet()
                                    selectedMangaPaths = pathsWithChanges
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030)),
                                modifier = Modifier.weight(1.2f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Select Changes", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    selectedMangaPaths = emptySet()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Clear All", fontSize = 12.sp)
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        groupedSources.forEach { source ->
                            val sourceCount = source.mangas.sumOf { mangaRenameCounts[it.absolutePath] ?: 0 }
                            
                            // Source Header item
                            item {
                                val isAllSelected = source.mangas.all { selectedMangaPaths.contains(it.absolutePath) }
                                val isCollapsed = collapsedSourceNames.contains(source.sourceName)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            collapsedSourceNames = if (isCollapsed) {
                                                collapsedSourceNames - source.sourceName
                                            } else {
                                                collapsedSourceNames + source.sourceName
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isAllSelected,
                                        onCheckedChange = { checked ->
                                            val allPaths = source.mangas.map { it.absolutePath }.toSet()
                                            selectedMangaPaths = if (checked == true) {
                                                selectedMangaPaths + allPaths
                                            } else {
                                                selectedMangaPaths - allPaths
                                            }
                                        },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    Text(
                                        text = if (sourceCount > 0) "${source.sourceName} ($sourceCount)" else source.sourceName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (sourceCount > 0) MaterialTheme.colorScheme.primary else Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Toggle Source",
                                        tint = Color.Gray,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            }

                            // Manga child items
                            val isCollapsed = collapsedSourceNames.contains(source.sourceName)
                            if (!isCollapsed) {
                                items(source.mangas) { manga ->
                                val isMangaChecked = selectedMangaPaths.contains(manga.absolutePath)
                                val count = mangaRenameCounts[manga.absolutePath] ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedMangaPaths = if (isMangaChecked) {
                                                selectedMangaPaths - manga.absolutePath
                                            } else {
                                                selectedMangaPaths + manga.absolutePath
                                            }
                                        }
                                        .padding(vertical = 2.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isMangaChecked,
                                        onCheckedChange = { checked ->
                                            selectedMangaPaths = if (checked == true) {
                                                selectedMangaPaths + manga.absolutePath
                                            } else {
                                                selectedMangaPaths - manga.absolutePath
                                            }
                                        },
                                        modifier = Modifier.scale(0.75f)
                                    )
                                    Text(
                                        text = if (count > 0) "${manga.mangaName} ($count)" else manga.mangaName,
                                        fontSize = 12.sp,
                                        color = if (count > 0) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (selectedMangaPaths.isEmpty()) {
                                Toast.makeText(context, "Please select at least one manga to scan", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val filtered = allScanResults.filter { group ->
                                selectedMangaPaths.contains(File(group.oldPath).parentFile?.absolutePath)
                            }
                            scanResults = filtered
                            logs = listOf(
                                "Starting Scan Process...",
                                "Filtering scanned results for selected mangas...",
                                "Scan completed: Found ${filtered.size} folders to rename."
                            )
                            currentStep = ScreenStep.SCAN_RESULTS
                            if (filtered.isEmpty()) {
                                Toast.makeText(context, "No chapters found that need renaming for selected mangas!", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Scan Selected Mangas (${selectedMangaPaths.size} folders)")
                    }
                }

                ScreenStep.SCAN_RESULTS -> {
                    val displayedResults = remember(scanResults, showLackingHashOnly) {
                        scanResults.map { group ->
                            val filteredOptions = group.options.filter { option ->
                                showLackingHashOnly || !option.lackingHashOnly
                            }
                            group.copy(options = filteredOptions)
                        }.filter { it.options.isNotEmpty() }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val selectedCount = displayedResults.flatMap { it.options }.count { it.selected }
                            Text("Proposed: ${displayedResults.size} folders ($selectedCount selected)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            TextButton(onClick = { currentStep = ScreenStep.TREE_SELECTION }) {
                                Text("Back to Selection", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLackingHashOnly = !showLackingHashOnly }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showLackingHashOnly,
                                onCheckedChange = { showLackingHashOnly = it },
                                modifier = Modifier.scale(0.8f)
                            )
                            Text(
                                text = "Show already recognized folders (lacking hash)",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        
                        val allScanlators = remember(scanResults) {
                            scanResults.flatMap { it.options }
                                .map { it.scanlator }
                                .filter { it.isNotBlank() && it != "None" }
                                .distinct()
                                .sorted()
                        }
                        
                        if (allScanlators.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Bulk Scanlator:",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.alignByBaseline()
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    Button(
                                        onClick = { scanlatorDropdownExpanded = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252525)),
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (selectedBulkScanlator.isEmpty()) "Select Scanlator..." else selectedBulkScanlator,
                                            fontSize = 12.sp,
                                            color = Color.White
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = scanlatorDropdownExpanded,
                                        onDismissRequest = { scanlatorDropdownExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("None / Reset Selection", fontSize = 12.sp) },
                                            onClick = {
                                                selectedBulkScanlator = ""
                                                scanlatorDropdownExpanded = false
                                                scanResults = scanResults.map { group ->
                                                    group.copy(options = group.options.map { it.copy(selected = false) })
                                                }
                                            }
                                        )
                                        allScanlators.forEach { scanlator ->
                                            DropdownMenuItem(
                                                text = { Text(scanlator, fontSize = 12.sp) },
                                                onClick = {
                                                    selectedBulkScanlator = scanlator
                                                    scanlatorDropdownExpanded = false
                                                    val targetScanlator = scanlator
                                                    addLog("Bulk selecting scanlator: $targetScanlator")
                                                    scanResults = scanResults.map { group ->
                                                        val matchingOption = group.options.find { it.scanlator == targetScanlator }
                                                        if (matchingOption != null) {
                                                            val newOptions = group.options.map { option ->
                                                                option.copy(selected = option.scanlator == targetScanlator)
                                                            }
                                                            group.copy(options = newOptions)
                                                        } else {
                                                            addLog("Manga: ${group.mangaTitle} - Folder '${group.oldName}' has no option for scanlator '$targetScanlator'. Please select manually.")
                                                            group
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    scanResults = scanResults.map { group ->
                                        group.copy(options = group.options.mapIndexed { idx, opt ->
                                            val shouldSelect = if (opt.lackingHashOnly && !showLackingHashOnly) {
                                                false
                                            } else {
                                                idx == 0
                                            }
                                            opt.copy(selected = shouldSelect)
                                        })
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Select All", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    scanResults = scanResults.map { group ->
                                        group.copy(options = group.options.map { opt ->
                                            opt.copy(selected = false)
                                        })
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Clear All", fontSize = 12.sp)
                            }
                        }
                    }

                    // Logs Console Panel
                    Text("Console Logs", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 12.sp)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                    ) {
                        val logListState = rememberLazyListState()
                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
                                logListState.animateScrollToItem(logs.size - 1)
                            }
                        }
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .padding(8.dp)
                                .fillMaxSize()
                        ) {
                            items(logs) { line ->
                                Text(
                                    text = line,
                                    color = Color(0xFF00FF00),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Scanned results list
                    if (scanResults.isNotEmpty()) {
                        if (displayedResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No folders need renaming.\n(Turn on the toggle above to show already recognized folders)",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                displayedResults.forEach { group ->
                                    // Group Header (Legacy folder description) - Horizontally scrollable
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                        ) {
                                            Text(
                                                text = "${group.mangaTitle} • ${group.oldName}",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                            )
                                        }
                                    }

                                    // Indented Options
                                    items(group.options) { option ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    scanResults = toggleOption(scanResults, group, option)
                                                }
                                                .padding(vertical = 4.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = option.selected,
                                                onCheckedChange = {
                                                    scanResults = toggleOption(scanResults, group, option)
                                                },
                                                modifier = Modifier.scale(0.8f)
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(start = 4.dp)
                                                    .horizontalScroll(rememberScrollState())
                                            ) {
                                                Text(
                                                    text = "${option.chapterName} [Scanlator: ${option.scanlator}]",
                                                    color = Color.LightGray,
                                                    fontSize = 12.sp
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text("➔", fontSize = 11.sp, color = Color.Gray)
                                                    Text(option.newName, fontSize = 11.sp, color = Color.Green)
                                                }
                                            }
                                        }
                                    }

                                    // Divider below group
                                    item {
                                        HorizontalDivider(color = Color(0xFF252525), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                                    }
                                }
                            }
                        }

                        // Apply button
                        Button(
                            onClick = {
                                val selectedTasks = scanResults.flatMap { group ->
                                    group.options.filter { it.selected }.map { option ->
                                        Pair(group, option)
                                    }
                                }
                                if (selectedTasks.isEmpty()) {
                                    Toast.makeText(context, "No tasks selected", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isRenaming = true
                                addLog("Starting folder renaming process...")
                                scope.launch(Dispatchers.IO) {
                                    var success = 0
                                    for ((group, option) in selectedTasks) {
                                        try {
                                            val oldFile = File(group.oldPath)
                                            val newFile = File(option.newPath)
                                            if (oldFile.exists()) {
                                                val renamed = oldFile.renameTo(newFile)
                                                if (renamed) {
                                                    success++
                                                    addLog("Renamed: '${group.oldName}' -> '${option.newName}'")
                                                } else {
                                                    addLog("Fail to rename: '${group.oldName}'")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            addLog("Error renaming '${group.oldName}': ${e.localizedMessage}")
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        addLog("Renaming completed: $success / ${selectedTasks.size} renamed successfully.")
                                        Toast.makeText(context, "Successfully renamed $success / ${selectedTasks.size} items!", Toast.LENGTH_LONG).show()
                                        isRenaming = false
                                        scanResults = emptyList() // clear list
                                        showCompletionDialog = true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRenaming,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                        ) {
                            if (isRenaming) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Renaming...", color = Color.Black)
                            } else {
                                Text("Apply Renames", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                ScreenStep.ABOUT -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(100.dp)
                        )
                        Text("TachiyomiSY Renamer", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text("Version 1.0.0", fontSize = 14.sp, color = Color.Gray)
                        Text(
                            "This app resolves issues where source extensions update their URLs, paths, or hashing schemes—which prevents older downloaded chapters from being recognized by Tachiyomi.\n\nIt scans your downloaded chapters, matches them against backup metadata, and renames them to match the new naming scheme expected by the updated extension.",
                            fontSize = 13.sp,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                        
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://github.com/itszux/TachiyomiSY-Chapter-Renamer"))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = "GitHub")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GitHub Repository")
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Button(
                            onClick = { currentStep = ScreenStep.CONFIGURATION },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}

// Grouped loading directory helper
fun loadSourcesAndMangas(downloadsPath: String): List<GroupedSource> {
    val downloadsDir = File(downloadsPath)
    if (!downloadsDir.exists() || !downloadsDir.isDirectory) {
        throw Exception("Downloads directory does not exist or is not a directory.")
    }
    val list = mutableListOf<GroupedSource>()
    val sourceDirs = downloadsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    for (sourceDir in sourceDirs) {
        val mangaDirs = sourceDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (mangaDirs.isNotEmpty()) {
            val mangas = mangaDirs.map {
                GroupedManga(
                    sourceName = sourceDir.name,
                    mangaName = it.name,
                    absolutePath = it.absolutePath
                )
            }.sortedBy { it.mangaName }
            list.add(GroupedSource(sourceDir.name, mangas))
        }
    }
    return list.sortedBy { it.sourceName }
}

// Protobuf & JSON Backup Parser
val SCHEMA = mapOf(
    "root" to mapOf(
        1 to Pair("backupManga", "list_manga"),
        2 to Pair("backupCategories", "list_category"),
        101 to Pair("backupSources", "list_source")
    ),
    "manga" to mapOf(
        1 to Pair("source", "int"),
        2 to Pair("url", "string"),
        3 to Pair("title", "string"),
        4 to Pair("artist", "string"),
        5 to Pair("author", "string"),
        6 to Pair("description", "string"),
        7 to Pair("genre", "list_string"),
        8 to Pair("status", "int"),
        9 to Pair("thumbnailUrl", "string"),
        13 to Pair("dateAdded", "int"),
        14 to Pair("viewer", "int"),
        16 to Pair("chapters", "list_chapter"),
        100 to Pair("favorite", "bool"),
        103 to Pair("viewer_flags", "int")
    ),
    "chapter" to mapOf(
        1 to Pair("url", "string"),
        2 to Pair("name", "string"),
        3 to Pair("scanlator", "string"),
        4 to Pair("read", "bool"),
        5 to Pair("bookmark", "bool"),
        9 to Pair("chapterNumber", "float")
    )
)

fun parseVarint(data: ByteArray, posRef: IntArray): Long {
    var result = 0L
    var shift = 0
    var pos = posRef[0]
    while (true) {
        val b = data[pos].toInt() and 0xFF
        pos++
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    posRef[0] = pos
    return result
}

@Suppress("UNCHECKED_CAST")
fun decodeMessage(data: ByteArray, schemaName: String): Map<String, Any> {
    val schema = SCHEMA[schemaName] ?: emptyMap()
    val parsed = mutableMapOf<String, Any>()
    val posRef = intArrayOf(0)
    val endPos = data.size
    
    while (posRef[0] < endPos) {
        val key = parseVarint(data, posRef)
        val wireType = (key and 0x07).toInt()
        val fieldNum = (key ushr 3).toInt()
        
        val schemaInfo = schema[fieldNum]
        val name = schemaInfo?.first ?: "field_$fieldNum"
        val fieldType = schemaInfo?.second ?: "unknown"
        
        var valObj: Any? = null
        
        when (wireType) {
            0 -> {
                val v = parseVarint(data, posRef)
                valObj = if (fieldType == "bool") v != 0L else v
            }
            1 -> {
                val pos = posRef[0]
                val bits = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
                posRef[0] = pos + 8
                valObj = if (fieldType == "float") java.lang.Double.longBitsToDouble(bits) else bits
            }
            2 -> {
                val length = parseVarint(data, posRef).toInt()
                val pos = posRef[0]
                val rawBytes = data.copyOfRange(pos, pos + length)
                posRef[0] = pos + length
                
                when (fieldType) {
                    "string" -> {
                        valObj = String(rawBytes, Charsets.UTF_8)
                    }
                    "list_manga" -> {
                        valObj = decodeMessage(rawBytes, "manga")
                    }
                    "list_chapter" -> {
                        valObj = decodeMessage(rawBytes, "chapter")
                    }
                    "list_category" -> {
                        valObj = decodeMessage(rawBytes, "category")
                    }
                    "list_source" -> {
                        valObj = decodeMessage(rawBytes, "source")
                    }
                    "list_string" -> {
                        valObj = String(rawBytes, Charsets.UTF_8)
                    }
                    else -> {
                        valObj = try {
                            String(rawBytes, Charsets.UTF_8)
                        } catch (e: Exception) {
                            rawBytes.joinToString("") { "%02x".format(it) }
                        }
                    }
                }
            }
            5 -> {
                val pos = posRef[0]
                val bits = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
                posRef[0] = pos + 4
                valObj = if (fieldType == "float") java.lang.Float.intBitsToFloat(bits).toDouble() else bits
            }
        }
        
        if (valObj != null) {
            val existing = parsed[name]
            if (existing != null) {
                if (existing is List<*>) {
                    parsed[name] = (existing as List<Any>) + valObj
                } else {
                    parsed[name] = listOf(existing, valObj)
                }
            } else {
                if (fieldType.startsWith("list_")) {
                    parsed[name] = listOf(valObj)
                } else {
                    parsed[name] = valObj
                }
            }
        }
    }
    return parsed
}

fun decompressGzip(input: ByteArray): ByteArray {
    val bis = ByteArrayInputStream(input)
    val gis = GZIPInputStream(bis)
    return gis.readBytes()
}

@Suppress("UNCHECKED_CAST")
fun parseBackup(fileBytes: ByteArray, onLog: (String) -> Unit): List<InternalManga> {
    if (fileBytes.size >= 2 && fileBytes[0] == 0x1F.toByte() && fileBytes[1] == 0x8B.toByte()) {
        onLog("Format: Gzip compressed Protobuf (.tachibk)")
        val decompressed = decompressGzip(fileBytes)
        onLog("Decompressed successful: ${decompressed.size} bytes")
        val decoded = decodeMessage(decompressed, "root")
        val parsed = fromProtobuf(decoded)
        onLog("Decoded ${parsed.size} manga entries from backup.")
        return parsed
    } else if (fileBytes.isNotEmpty() && fileBytes[0] == '{'.code.toByte()) {
        onLog("Format: JSON file")
        val jsonString = String(fileBytes, Charsets.UTF_8)
        val parsed = fromJson(JSONObject(jsonString))
        onLog("Parsed ${parsed.size} manga entries from JSON.")
        return parsed
    } else {
        onLog("Format: Uncompressed Protobuf")
        val decoded = decodeMessage(fileBytes, "root")
        val parsed = fromProtobuf(decoded)
        onLog("Decoded ${parsed.size} manga entries.")
        return parsed
    }
}

@Suppress("UNCHECKED_CAST")
fun fromProtobuf(root: Map<String, Any>): List<InternalManga> {
    val rawManga = root["backupManga"] ?: return emptyList()
    val mangaList = if (rawManga is List<*>) rawManga as List<Map<String, Any>> else listOf(rawManga as Map<String, Any>)
    
    return mangaList.map { m ->
        val title = m["title"] as? String ?: ""
        val rawChapters = m["chapters"] ?: emptyList<Any>()
        val chList = if (rawChapters is List<*>) rawChapters as List<Map<String, Any>> else listOf(rawChapters as Map<String, Any>)
        val chapters = chList.map { c ->
            InternalChapter(
                url = c["url"] as? String ?: "",
                name = c["name"] as? String ?: "",
                scanlator = c["scanlator"] as? String,
                chapterNumber = (c["chapterNumber"] as? Number)?.toDouble() ?: -1.0
            )
        }
        InternalManga(title, chapters)
    }
}

fun fromJson(jsonRoot: JSONObject): List<InternalManga> {
    val mangaList = mutableListOf<InternalManga>()
    val backupManga = jsonRoot.optJSONArray("backupManga") ?: return emptyList()
    for (i in 0 until backupManga.length()) {
        val m = backupManga.getJSONObject(i)
        val title = m.optString("title", "")
        val chList = mutableListOf<InternalChapter>()
        val chapters = m.optJSONArray("chapters")
        if (chapters != null) {
            for (j in 0 until chapters.length()) {
                val c = chapters.getJSONObject(j)
                chList.add(
                    InternalChapter(
                        url = c.optString("url", ""),
                        name = c.optString("name", ""),
                        scanlator = if (c.has("scanlator") && !c.isNull("scanlator")) c.optString("scanlator") else null,
                        chapterNumber = c.optDouble("chapterNumber", -1.0)
                    )
                )
            }
        }
        mangaList.add(InternalManga(title, chList))
    }
    return mangaList
}

// Matching Utilities
fun getUrlHash(url: String): String {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(6)
}

fun buildValidFilename(name: String, maxBytes: Int = 229): String {
    val trimmed = name.trim('.', ' ')
    if (trimmed.isEmpty()) return "(invalid)"
    val sb = StringBuilder(trimmed.length)
    trimmed.forEach { c ->
        if (c.code <= 0x1f || c == '"' || c == '*' || c == '/' || c == ':' || c == '<' || c == '>' || c == '?' || c == '\\' || c == '|' || c == 0x7f.toChar()) {
            sb.append('_')
        } else {
            sb.append(c)
        }
    }
    val s = sb.toString()
    val bytes = s.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return s
    
    val charset = Charsets.UTF_8
    val decoder = charset.newDecoder()
    val bb = java.nio.ByteBuffer.wrap(bytes, 0, maxBytes)
    val cb = java.nio.CharBuffer.allocate(maxBytes)
    decoder.onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
    decoder.decode(bb, cb, true)
    decoder.flush(cb)
    return String(cb.array(), 0, cb.position())
}

fun getChapterDirName(chapterName: String, chapterScanlator: String?, chapterUrl: String): String {
    val sanitized = if (chapterName.isBlank()) "Chapter" else chapterName
    var dirName = sanitized
    if (!chapterScanlator.isNullOrBlank()) {
        dirName = "${chapterScanlator}_$dirName"
    }
    dirName = buildValidFilename(dirName)
    return "${dirName}_${getUrlHash(chapterUrl)}"
}

fun containsChapterNumber(itemNoExt: String, numStr: String): Boolean {
    var index = 0
    while (true) {
        val foundIndex = itemNoExt.indexOf(numStr, index, ignoreCase = true)
        if (foundIndex == -1) return false
        
        val precedingChar = if (foundIndex > 0) itemNoExt[foundIndex - 1] else null
        val precedingOk = precedingChar == null || (!precedingChar.isDigit() && precedingChar != '.')
        
        val nextIndex = foundIndex + numStr.length
        val succeedingChar = if (nextIndex < itemNoExt.length) itemNoExt[nextIndex] else null
        
        var succeedingOk = true
        if (succeedingChar != null) {
            if (succeedingChar.isDigit()) {
                succeedingOk = false
            } else if (succeedingChar == '.') {
                val afterDotIndex = nextIndex + 1
                if (afterDotIndex < itemNoExt.length && itemNoExt[afterDotIndex].isDigit()) {
                    succeedingOk = false
                }
            }
        }
        
        if (precedingOk && succeedingOk) {
            return true
        }
        
        index = foundIndex + 1
    }
}

fun scanFolders(
    mangaList: List<InternalManga>,
    selectedMangas: List<GroupedManga>,
    onLog: (String) -> Unit
): List<FolderRenameGroup> {
    val groupsMap = mutableMapOf<String, Pair<String, String>>()
    val optionsMap = mutableMapOf<String, MutableList<RenameOption>>()
    onLog("Scanning ${selectedMangas.size} selected manga folders...")
    
    for (manga in selectedMangas) {
        val mangaDir = File(manga.absolutePath)
        if (!mangaDir.exists() || !mangaDir.isDirectory) continue
        
        val matchedManga = mangaList.firstOrNull {
            buildValidFilename(it.title) == buildValidFilename(mangaDir.name) || it.title == mangaDir.name
        } ?: continue
        
        val existingItems = mangaDir.listFiles()
            ?.map { it.name }
            ?.filter { !it.endsWith("_tmp") && !it.endsWith("_tmp.cbz") }
            ?: emptyList()
            
        val correctTargetNames = matchedManga.chapters.map {
            getChapterDirName(it.name, it.scanlator, it.url)
        }.flatMap { listOf(it, "$it.cbz") }.toSet()
        
        for (chapter in matchedManga.chapters) {
            if (chapter.url.isEmpty()) continue
            
            val targetName = getChapterDirName(chapter.name, chapter.scanlator, chapter.url)
            val targetCbz = "$targetName.cbz"
            
            if (targetName in existingItems || targetCbz in existingItems) {
                continue // Already correctly named!
            }
            
            var numStr = chapter.chapterNumber.toString()
            if (numStr.endsWith(".0")) {
                numStr = numStr.dropLast(2)
            }
            
            val legacyNoScanlator = buildValidFilename(if (chapter.name.isBlank()) "Chapter" else chapter.name)
            val legacyWithScanlator = buildValidFilename(if (!chapter.scanlator.isNullOrBlank()) "${chapter.scanlator}_${chapter.name}" else chapter.name)
            
            val candidates = existingItems.filter { item ->
                if (item in correctTargetNames) return@filter false
                
                val itemNoExt = if (item.endsWith(".cbz")) item.dropLast(4) else item
                
                itemNoExt == legacyNoScanlator || 
                itemNoExt == legacyWithScanlator || 
                containsChapterNumber(itemNoExt, numStr)
            }
            
            for (cand in candidates) {
                val isCbz = cand.endsWith(".cbz")
                val finalTarget = if (isCbz) targetCbz else targetName
                val oldPath = File(mangaDir, cand).absolutePath
                
                val itemNoExt = if (cand.endsWith(".cbz")) cand.dropLast(4) else cand
                val isLackingHash = !chapter.scanlator.isNullOrBlank() && itemNoExt == legacyWithScanlator
                
                groupsMap[oldPath] = Pair(matchedManga.title, cand)
                
                val options = optionsMap.getOrPut(oldPath) { mutableListOf() }
                if (options.none { it.newName == finalTarget }) {
                    options.add(
                        RenameOption(
                            newName = finalTarget,
                            newPath = File(mangaDir, finalTarget).absolutePath,
                            scanlator = chapter.scanlator ?: "None",
                            chapterName = chapter.name,
                            selected = false,
                            lackingHashOnly = isLackingHash
                        )
                    )
                }
            }
        }
    }
    
    val list = groupsMap.map { (oldPath, pair) ->
        val (mangaTitle, oldName) = pair
        val options = optionsMap[oldPath] ?: emptyList()
        val mappedOptions = if (options.size == 1 && !options[0].lackingHashOnly) {
            listOf(options[0].copy(selected = true))
        } else {
            options
        }
        FolderRenameGroup(
            mangaTitle = mangaTitle,
            oldName = oldName,
            oldPath = oldPath,
            options = mappedOptions
        )
    }
    return list
}

fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    if ("com.android.externalstorage.documents" == uri.authority) {
        val docId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                uri.path
            }
        } else {
            uri.path
        } ?: return null
        
        val split = docId.split(":")
        if (split.size >= 2) {
            val type = split[0]
            val relativePath = split[1]
            if ("primary".equals(type, ignoreCase = true)) {
                return Environment.getExternalStorageDirectory().toString() + "/" + relativePath
            } else {
                val storagePath = "/storage/${type}"
                if (File(storagePath).exists()) {
                    return storagePath + "/" + relativePath
                }
            }
        }
    }
    return uri.path
}
