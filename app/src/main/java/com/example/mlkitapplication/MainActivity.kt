package com.example.mlkitapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mlkitapplication.scan.BarcodeScanEngine
import com.example.mlkitapplication.scan.ScanScreen
import com.example.mlkitapplication.ui.theme.MlKitApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var engine: BarcodeScanEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scanner = ensureEngine()
        setContent {
            // 扫码界面自带深色配色，关掉动态取色避免预览被主题色干扰
            MlKitApplicationTheme(darkTheme = true, dynamicColor = false) {
                ScanApp(engine = scanner)
            }
        }
        handleScanImageIntent(savedInstanceState, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScanImageIntent(null, intent)
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    private fun ensureEngine(): BarcodeScanEngine =
        engine ?: BarcodeScanEngine(applicationContext, this).also { engine = it }

    /**
     * `adb shell am start -n com.example.mlkitapplication/.MainActivity --es scan_image <文件路径>`
     * 直接解码一张本地图片，用于离线验证 bundled 模型的解码链路。
     */
    private fun handleScanImageIntent(bundle: Bundle?, intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_SCAN_IMAGE)
            ?: bundle?.getString(EXTRA_SCAN_IMAGE)
            ?: return
        lifecycleScope.launch { engine?.scanFromFilePath(path) }
    }

    companion object {
        const val EXTRA_SCAN_IMAGE = "scan_image"
    }
}

@Composable
private fun ScanApp(engine: BarcodeScanEngine) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    if (granted) {
        // 引擎由 Activity 持有，释放在 onDestroy，这里不需要额外回收
        ScanScreen(engine = engine, onBack = { (context as? ComponentActivity)?.finish() })
    } else {
        // 首次进入自动弹一次系统授权，被拒后停留在可重试的引导页
        LaunchedEffect(asked) {
            if (!asked) {
                asked = true
                requestPermission.launch(Manifest.permission.CAMERA)
            }
        }
        PermissionRationale(onRetry = { requestPermission.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionRationale(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F11))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("需要相机权限", color = Color.White, fontSize = 20.sp)
        Text(
            "扫码演示全程在本机完成：模型内置在 APK 里，画面不上传、不联网。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(onClick = onRetry) { Text("授予相机权限") }
        Text(
            "ML Kit barcode-scanning 17.3.0 · CameraX · 自动缩放",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}
