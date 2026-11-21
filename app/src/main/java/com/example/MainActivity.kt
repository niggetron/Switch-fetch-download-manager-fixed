package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.model.NewDownloadRequest
import com.example.ui.AetherApp
import com.example.ui.AetherViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AetherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Intake is handled only on a fresh creation (C4/C12): on a configuration
        // change onCreate runs again with the same Intent, which previously
        // double-enqueued the same URL. setIntent clears it after handling.
        if (savedInstanceState == null) {
            // Browser/Share-sheet intake: a shared http(s) link is queued directly.
            if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Regex("https?://[^\\s]+").find(shared)?.value?.let { url ->
                    viewModel.enqueueDownload(NewDownloadRequest(url = url))
                }
                setIntent(Intent())
            } else {
                // C12: the benchmark harness (scripts/bench_device.sh) sends these
                // extras; previously MainActivity ignored them so the script's
                // TEST_URL was inert and no transfer was actually driven.
                val benchUrl = intent?.getStringExtra("aether_bench_url")
                if (!benchUrl.isNullOrBlank()) {
                    val multipath = intent?.getBooleanExtra("aether_bench_multipath", true) != false
                    viewModel.enqueueDownload(
                        NewDownloadRequest(
                            url = benchUrl,
                            routeMode = if (multipath) {
                                com.example.data.model.NetworkRouteMode.AUTO_MULTIPATH
                            } else {
                                com.example.data.model.NetworkRouteMode.WIFI_ONLY
                            },
                        )
                    )
                    setIntent(Intent())
                }
            }
        }
        setContent {
            AetherApp(viewModel = viewModel)
        }
    }
}
