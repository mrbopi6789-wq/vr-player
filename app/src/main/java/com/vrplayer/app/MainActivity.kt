package com.vrplayer.app

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vrplayer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playerLeft: MediaPlayer? = null
    private var playerRight: MediaPlayer? = null
    private var isPlaying: Boolean = false
    private var isPreparedLeft: Boolean = false
    private var isPreparedRight: Boolean = false
    private var isMuted: Boolean = false
    private var controlsVisible: Boolean = true
    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    companion object {
        private const val VIDEO_PICK_REQUEST = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        setupButtons()
        showHomeScreen()
    }

    private fun setupButtons() {
        binding.btnPickVideo.setOnClickListener { openGallery() }
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) pauseVideo() else playVideo()
        }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnBack.setOnClickListener {
            stopAndReset()
            showHomeScreen()
        }
        binding.tapOverlay.setOnClickListener { toggleControls() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pos = progress * (playerLeft?.duration ?: 0) / 100
                    playerLeft?.seekTo(pos)
                    playerRight?.seekTo(pos)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun showHomeScreen() {
        binding.homeScreen.visibility = View.VISIBLE
        binding.playerScreen.visibility = View.GONE
        binding.controlsOverlay.visibility = View.GONE
        binding.tapOverlay.visibility = View.GONE
    }

    private fun showPlayerScreen() {
        binding.homeScreen.visibility = View.GONE
        binding.playerScreen.visibility = View.VISIBLE
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.tapOverlay.visibility = View.VISIBLE
        handler.postDelayed({ hideControls() }, 3000)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsOverlay.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed({ hideControls() }, 3000)
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsOverlay.animate().alpha(0f).setDuration(500).start()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
        startActivityForResult(intent, VIDEO_PICK_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VIDEO_PICK_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> loadVideo(uri) }
        }
    }

    private fun loadVideo(uri: Uri) {
        showPlayerScreen()
        isPreparedLeft = false
        isPreparedRight = false
        playerLeft?.release()
        playerRight?.release()
        binding.tvVideoName.text = getVideoName(uri)

        val leftPlayer = MediaPlayer()
        leftPlayer.setDataSource(this, uri)
        leftPlayer.setDisplay(binding.surfaceLeft.holder)
        leftPlayer.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
        leftPlayer.setOnPreparedListener {
            isPreparedLeft = true
            tryStartPlayback()
        }
        leftPlayer.setOnCompletionListener {
            isPlaying = false
            updatePlayPauseButton()
            handler.removeCallbacks(progressRunnable)
        }
        leftPlayer.setOnErrorListener { _, _, _ -> true }
        leftPlayer.prepareAsync()
        playerLeft = leftPlayer

        val rightPlayer = MediaPlayer()
        rightPlayer.setDataSource(this, uri)
        rightPlayer.setDisplay(binding.surfaceRight.holder)
        rightPlayer.setVolume(0f, 0f)
        rightPlayer.setOnPreparedListener {
            isPreparedRight = true
            tryStartPlayback()
        }
        rightPlayer.setOnErrorListener { _, _, _ -> true }
        rightPlayer.prepareAsync()
        playerRight = rightPlayer
    }

    private fun tryStartPlayback() {
        if (!isPreparedLeft || !isPreparedRight) return
        playerLeft?.start()
        playerRight?.start()
        isPlaying = true
        updatePlayPauseButton()
        handler.post(progressRunnable)
    }

    private fun playVideo() {
        playerLeft?.start()
        playerRight?.start()
        isPlaying = true
        updatePlayPauseButton()
        handler.post(progressRunnable)
    }

    private fun pauseVideo() {
        playerLeft?.pause()
        playerRight?.pause()
        isPlaying = false
        updatePlayPauseButton()
    }

    private fun stopAndReset() {
        handler.removeCallbacks(progressRunnable)
        playerLeft?.stop()
        playerLeft?.release()
        playerLeft = null
        playerRight?.stop()
        playerRight?.release()
        playerRight = null
        isPlaying = false
        isPreparedLeft = false
        isPreparedRight = false
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
    }

    private fun updatePlayPauseButton() {
        binding.btnPlayPause.text = if (isPlaying) "⏸" else "▶"
    }

    private fun updateProgress() {
        val player = playerLeft ?: return
        val duration = player.duration
        val position = player.currentPosition
        if (duration > 0) {
            binding.seekBar.progress = position * 100 / duration
            binding.tvCurrentTime.text = formatTime(position)
            binding.tvTotalTime.text = formatTime(duration)
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun getVideoName(uri: Uri): String {
        var name = "Video"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {}
        return name.removeSuffix(".mp4").removeSuffix(".mkv").removeSuffix(".mov")
    }

    private fun showSettingsDialog() {
        pauseVideo()
        val options = arrayOf(
            if (isMuted) "🔊 Unmute Audio" else "🔇 Mute Audio",
            "↩️ Restart Video",
            "⏭ Skip Forward 10s",
            "⏮ Skip Back 10s",
            "❌ Close Video"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("VR Player Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleMute()
                    1 -> {
                        playerLeft?.seekTo(0)
                        playerRight?.seekTo(0)
                        playVideo()
                    }
                    2 -> {
                        val pos = (playerLeft?.currentPosition ?: 0) + 10000
                        playerLeft?.seekTo(pos)
                        playerRight?.seekTo(pos)
                        playVideo()
                    }
                    3 -> {
                        val pos = maxOf(0, (playerLeft?.currentPosition ?: 0) - 10000)
                        playerLeft?.seekTo(pos)
                        playerRight?.seekTo(pos)
                        playVideo()
                    }
                    4 -> {
                        stopAndReset()
                        showHomeScreen()
                    }
                }
            }
            .setOnDismissListener { if (!isPlaying) playVideo() }
            .show()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val vol = if (isMuted) 0f else 1f
        playerLeft?.setVolume(vol, vol)
        Toast.makeText(
            this,
            if (isMuted) "Muted 🔇" else "Unmuted 🔊",
            Toast.LENGTH_SHORT
        ).show()
        playVideo()
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        playerLeft?.release()
        playerRight?.release()
    }
}
