package com.example.medicinechecker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.medicinechecker.ui.theme.MedicineCheckerTheme
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

private const val GEMINI_API_KEY = "AIzaSyBwynbKL2bLl13avn2hfTzlDJsVXvdlYrk"

//region Data Classes for Gemini API
@Serializable
data class GeminiVisionRequest(val contents: List<Content>)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(
    val text: String? = null,
    val inline_data: InlineData? = null
)

@Serializable
data class InlineData(val mime_type: String, val data: String)

@Serializable
data class GeminiResponse(val candidates: List<Candidate>? = null, val error: ApiError? = null)

@Serializable
data class Candidate(val content: Content?)

@Serializable
data class ApiError(val message: String)
//endregion

class MainActivity : ComponentActivity() {
    private val httpClient: HttpClient by lazy(LazyThreadSafetyMode.NONE) {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { isLenient = true; ignoreUnknownKeys = true })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedicineCheckerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MedicineAnalysisScreen(httpClient)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        httpClient.close()
    }
}

@Composable
fun MedicineAnalysisScreen(httpClient: HttpClient) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var resultText by rememberSaveable { mutableStateOf("Press a button to analyze a medicine photo.") }
    var isProcessing by remember { mutableStateOf(false) }
    var isSourceMenuOpen by remember { mutableStateOf(false) }
    val pendingCameraUri = remember { mutableStateOf<Uri?>(null) }

    fun processImage(uri: Uri, ownsUri: Boolean) {
        coroutineScope.launch {
            isProcessing = true
            val result = runCatching {
                val bitmap = decodeBitmapForUpload(context, uri)
                processImageWithVisionModel(bitmap, httpClient)
            }.fold(
                onSuccess = { it },
                onFailure = {
                    Log.e("MedicineAnalysis", "Failed to process image", it)
                    "处理失败：${it.localizedMessage ?: "未知错误"}"
                }
            )
            resultText = result
            if (ownsUri) {
                cleanupTemporaryImage(context, uri)
            }
            isProcessing = false
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let { processImage(it, ownsUri = false) }
        }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val capturedUri = pendingCameraUri.value
            pendingCameraUri.value = null
            if (success && capturedUri != null) {
                processImage(capturedUri, ownsUri = true)
            } else if (capturedUri != null) {
                cleanupTemporaryImage(context, capturedUri)
            }
            if (!success) {
                isProcessing = false
            }
        }
    )

    fun launchCameraWithPermission() {
        val uri = createImageUri(context)
        if (uri != null) {
            pendingCameraUri.value = uri
            try {
                cameraLauncher.launch(uri)
            } catch (e: ActivityNotFoundException) {
                Log.e("MedicineAnalysis", "Camera app not found", e)
                resultText = "无法启动相机：未找到相机应用。请在模拟器或设备上安装一个相机应用。"
                isProcessing = false // Reset processing state
            }
        } else {
            resultText = "无法创建用于拍照的缓存文件，请检查存储空间。"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                launchCameraWithPermission()
            } else {
                resultText = "相机权限被拒绝。无法使用相机功能。"
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE0E7FF),
                        Color(0xFFC0D0FF)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(text = "LLM Response:", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.5f))
            ) {
                Text(
                    text = resultText,
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isProcessing) {
                CircularProgressIndicator()
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { isSourceMenuOpen = true },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.7f))
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.CameraAlt,
                            contentDescription = "选择拍摄或选取照片",
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = isSourceMenuOpen,
                        onDismissRequest = { isSourceMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("从相册选择") },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = {
                                isSourceMenuOpen = false
                                imagePickerLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("使用相机拍摄") },
                            leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                            onClick = {
                                isSourceMenuOpen = false
                                when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                                    PackageManager.PERMISSION_GRANTED -> launchCameraWithPermission()
                                    else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在性能较低的设备上运行时，请尽量在光线充足处拍摄以提升识别准确率。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private suspend fun decodeBitmapForUpload(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap {
    return withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val largestSide = max(width, height).coerceAtLeast(1)
                if (largestSide > maxDimension) {
                    val ratio = largestSide.toFloat() / maxDimension.toFloat()
                    val targetWidth = max(1, (width / ratio).roundToInt())
                    val targetHeight = max(1, (height / ratio).roundToInt())
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        } else {
            val contentResolver = context.contentResolver
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

            val originalWidth = boundsOptions.outWidth.takeIf { it > 0 } ?: maxDimension
            val originalHeight = boundsOptions.outHeight.takeIf { it > 0 } ?: maxDimension
            val largestSide = max(originalWidth, originalHeight)
            var sampleSize = 1
            while (largestSide / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: throw IOException("无法解码图片")
        }
    }
}

suspend fun processImageWithVisionModel(bitmap: Bitmap, httpClient: HttpClient): String {
    return withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val prompt =
                "这是什么药品？请用中文为急救人员提供一份关于它的摘要，包括：一句话简介、主要用途、禁忌症、紧急情况下的常用剂量和关键副作用。如果图片不清晰或无法识别，请直接说明。"

            val requestBody = GeminiVisionRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = prompt),
                            Part(inline_data = InlineData(mime_type = "image/jpeg", data = base64Image))
                        )
                    )
                )
            )

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"
            val response: GeminiResponse = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            when {
                response.error != null -> "API Error: ${response.error.message}"
                else -> response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "Gemini returned an empty or invalid response."
            }
        } catch (e: Exception) {
            Log.e("ProcessingError", "An error occurred", e)
            "Fatal Error: ${e.message}"
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}

private fun createImageUri(context: Context): Uri? {
    val cacheDir = File(context.cacheDir, "captures")
    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
        Log.e("MedicineAnalysis", "Unable to create cache directory for captures")
        return null
    }
    return try {
        val imageFile = File.createTempFile("capture_", ".jpg", cacheDir)
        FileProvider.getUriForFile(context, "com.example.medicinechecker.fileprovider", imageFile)
    } catch (e: IOException) {
        Log.e("MedicineAnalysis", "Failed to create image file for camera capture", e)
        null
    }
}

private fun cleanupTemporaryImage(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
        .onFailure { Log.w("MedicineAnalysis", "Failed to remove temp image", it) }
}
