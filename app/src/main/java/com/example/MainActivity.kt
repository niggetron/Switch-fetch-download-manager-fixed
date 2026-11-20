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
        // Browser/Share-sheet intake: a shared http(s) link is queued directly.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("https?://[^\\s]+").find(shared)?.value?.let { url ->
                viewModel.enqueueDownload(NewDownloadRequest(url = url))
            }
        }
        setContent {
            AetherApp(viewModel = viewModel)
        }
    }
}
