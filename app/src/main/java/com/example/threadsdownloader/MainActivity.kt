package com.example.threadsdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabDownload: ImageButton  // 改為 ImageButton
    private lateinit var fabRefresh: ImageButton   // 改為 ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 設定狀態列為半透明
        window.statusBarColor = android.graphics.Color.parseColor("#33000000")

        // 初始化視圖
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        fabDownload = findViewById(R.id.fabDownload)
        fabRefresh = findViewById(R.id.fabRefresh)

        setupWebView()
        setupButtons()
        checkPermissions()
        setupBackPressHandler()

        // 載入 Threads 網站
        webView.loadUrl("https://www.threads.net/")
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                fabDownload.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }
    }

    private fun setupButtons() {
        fabDownload.setOnClickListener {
            Toast.makeText(this, "正在分析頁面影片...", Toast.LENGTH_SHORT).show()
            extractAndDownloadVideos()
        }

        fabRefresh.setOnClickListener {
            webView.reload()
            Toast.makeText(this, "重新整理", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAndDownloadVideos() {
        val jsCode = """
            (function() {
                const videos = [];
                const videoElements = document.querySelectorAll('video');
                
                videoElements.forEach((video, index) => {
                    const url = video.src || video.currentSrc;
                    if (url) {
                        let container = video.closest('article');
                        if (!container) {
                            container = video.parentElement;
                            while (container && container.parentElement) {
                                if (container.querySelector('a[href*="/@"]')) break;
                                container = container.parentElement;
                            }
                        }
                        
                        let username = 'unknown';
                        const userLinks = container ? container.querySelectorAll('a[href*="/@"]') : [];
                        if (userLinks.length > 0) {
                            const href = userLinks[0].href;
                            const match = href.match(/@([^\/\?]+)/);
                            if (match) username = match[1];
                        }
                        
                        let postText = '';
                        const textElements = container ? container.querySelectorAll('span[dir="auto"]') : [];
                        for (let el of textElements) {
                            const text = el.textContent.trim();
                            if (text.length > 20 && !text.includes('Follow')) {
                                postText = text.substring(0, 150);
                                break;
                            }
                        }
                        
                        let thumbnail = video.poster || '';
                        let duration = video.duration || 0;
                        
                        videos.push({
                            url: url,
                            index: index,
                            username: username,
                            text: postText,
                            thumbnail: thumbnail,
                            duration: Math.round(duration)
                        });
                    }
                });
                
                return JSON.stringify(videos);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val jsonStr = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")

                if (jsonStr != "[]" && jsonStr != "null") {
                    val videos = parseVideosJson(jsonStr)
                    if (videos.isNotEmpty()) {
                        showEnhancedVideoSelector(videos)
                    } else {
                        Toast.makeText(this, "未找到影片", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "此頁面沒有影片，請滑動到有影片的貼文", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "提取失敗，請重試", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseVideosJson(jsonStr: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                videos.add(
                    VideoInfo(
                        url = obj.getString("url"),
                        index = obj.getInt("index"),
                        username = obj.optString("username", "unknown"),
                        text = obj.optString("text", ""),
                        thumbnail = obj.optString("thumbnail", ""),
                        duration = obj.optInt("duration", 0)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return videos
    }

    private fun showEnhancedVideoSelector(videos: List<VideoInfo>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_video_selector, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val btnDownloadAll = dialogView.findViewById<Button>(R.id.btnDownloadAll)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSelectAll = dialogView.findViewById<Button>(R.id.btnSelectAll)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)

        tvTitle.text = "找到 ${videos.size} 個影片"

        val selectedVideos = mutableSetOf<VideoInfo>()
        var isAllSelected = false

        fun updateDownloadButton() {
            btnDownloadAll.text = if (selectedVideos.isEmpty()) {
                "請選擇影片"
            } else {
                "下載 ${selectedVideos.size} 個影片"
            }
            btnDownloadAll.isEnabled = selectedVideos.isNotEmpty()
        }

        val adapter = VideoAdapter(videos, selectedVideos) { video, isSelected ->
            if (isSelected) {
                selectedVideos.add(video)
            } else {
                selectedVideos.remove(video)
            }

            isAllSelected = selectedVideos.size == videos.size
            btnSelectAll.text = if (isAllSelected) "取消全選" else "全選"

            updateDownloadButton()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnSelectAll.setOnClickListener {
            isAllSelected = !isAllSelected

            if (isAllSelected) {
                selectedVideos.clear()
                selectedVideos.addAll(videos)
                btnSelectAll.text = "取消全選"
            } else {
                selectedVideos.clear()
                btnSelectAll.text = "全選"
            }

            adapter.notifyDataSetChanged()
            updateDownloadButton()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnDownloadAll.setOnClickListener {
            if (selectedVideos.isNotEmpty()) {
                downloadVideos(selectedVideos.toList())
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        updateDownloadButton()
        dialog.show()
    }

    private fun downloadVideos(videos: List<VideoInfo>) {
        if (!checkPermissions()) {
            return
        }

        videos.forEach { video ->
            downloadVideo(video)
        }

        Toast.makeText(this, "已加入 ${videos.size} 個下載任務", Toast.LENGTH_LONG).show()
    }

    private fun downloadVideo(video: VideoInfo) {
        try {
            val timestamp = System.currentTimeMillis()
            val safeUsername = video.username.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val filename = "${safeUsername}_video_${video.index + 1}_$timestamp.mp4"

            val request = DownloadManager.Request(Uri.parse(video.url))
                .setTitle("@${video.username} 的影片")
                .setDescription("Threads 影片下載中...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Threads/$filename")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

        } catch (e: Exception) {
            Toast.makeText(this, "下載失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return true
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "權限已授予，請再試一次", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要存儲權限才能下載影片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class VideoInfo(
        val url: String,
        val index: Int,
        val username: String,
        val text: String,
        val thumbnail: String,
        val duration: Int
    )

    inner class VideoAdapter(
        private val videos: List<VideoInfo>,
        private val selectedVideos: MutableSet<VideoInfo>,
        private val onSelectionChanged: (VideoInfo, Boolean) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
            val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
            val tvUsername: TextView = itemView.findViewById(R.id.tvUsername)
            val tvText: TextView = itemView.findViewById(R.id.tvText)
            val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
            val container: View = itemView.findViewById(R.id.container)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val video = videos[position]

            holder.tvIndex.text = "影片 ${video.index + 1}"
            holder.tvUsername.text = "@${video.username}"
            holder.tvText.text = if (video.text.isEmpty()) "（無文字內容）" else video.text

            if (video.duration > 0) {
                val minutes = video.duration / 60
                val seconds = video.duration % 60
                holder.tvDuration.text = String.format("%d:%02d", minutes, seconds)
                holder.tvDuration.visibility = View.VISIBLE
            } else {
                holder.tvDuration.visibility = View.GONE
            }

            holder.checkBox.isChecked = selectedVideos.contains(video)

            holder.container.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }

            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(video, isChecked)
            }
        }

        override fun getItemCount() = videos.size
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}