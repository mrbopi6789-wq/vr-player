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
import android.view.SurfaceHolder
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

    // Two MediaPlayers — one for each eye
    private var playerLeft: MediaPlayer? = null
    private var playerRight: MediaPlayer? = null

    private var videoUri: Uri? = null
    private var isPlaying = false
    private var isPreparedLeft = false
    private var isPreparedRight = false

    // Settings
    private var isMuted = false
    private var playbackSpeed = 1.0f
    private var brightness = 1.0f

    // Progress bar update handler
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

        setupSurfaces()
        setupButtons()

        // Show home screen initially
        showHomeScreen()
    }

    // -------------------------------------------------------------------------
    // UI Setup
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        // Pick video button
        binding.btnPickVideo.setOnClickListener {
            openGallery()
        }

        // Play/Pause button
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) pauseVideo() else playVideo()
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Seekbar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pos = (progress * (playerLeft?.duration ?: 0) / 100)
                    playerLeft?.seekTo(pos)
                    playerRight?.seekTo(pos)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tap screen to show/hide controls
        binding.tapOverlay.setOnClickListener {
            toggleControls()
        }

        // Back button on player screen
        binding.btnBack.setOnClickListener {
            stopAndReset()
            showHomeScreen()
        }
    }

    private fun setupSurfaces() {
        // Left surface
        binding.surfaceLeft.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        // Right surface
        binding.surfaceRight.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    // -------------------------------------------------------------------------
    // Screen states
    // -------------------------------------------------------------------------

    private fun showHomeScreen() {
        binding.homeScreen.visibility = View.VISIBLE
        binding.playerScreen.visibility = View.GONE
        binding.controlsOverlay.visibility = View.GONE
    }

    private fun showPlayerScreen() {
        binding.homeScreen.visibility = View.GONE
        binding.playerScreen.visibility = View.VISIBLE
        binding.controlsOverlay.visibility = View.VISIBLE

        // Auto hide controls after 3 seconds
        handler.postDelayed({ hideControls() }, 3000)
    }

    private var controlsVisible = true

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

    // -------------------------------------------------------------------------
    // Video picking
    // -------------------------------------------------------------------------

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
        }
        startActivityForResult(intent, VIDEO_PICK_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VIDEO_PICK_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                videoUri = uri
                loadVideo(uri)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Video playback
    // -------------------------------------------------------------------------

    private fun loadVideo(uri: Uri) {
        showPlayerScreen()
        isPreparedLeft = false
        isPreparedRight = false

        // Show video name
        val name = getVideoName(uri)
        binding.tvVideoName.text = name

        // Release old players
        playerLeft?.release()
        playerRight?.release()

        // Create left player
        playerLeft = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            setDisplay(binding.surfaceLeft.holder)
            setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
            isLooping = false
            setOnPreparedListener {
                isPreparedLeft = true
                tryStartPlayback()
            }
            setOnCompletionListener {
                isPlaying = false
                updatePlayPauseButton()
                handler.removeCallbacks(progressRunnable)
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Error playing video", Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }

        // Create right player — same video, no audio
        playerRight = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            setDisplay(binding.surfaceRight.holder)
            setVolume(0f, 0f)  // Always muted — audio comes from left player only
            isLooping = false
            setOnPreparedListener {
                isPreparedRight = true
                tryStartPlayback()
            }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
    }

    private fun tryStartPlayback() {
        // Only start when BOTH players are ready
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
            binding.seekBar.progress = (position * 100 / duration)
            binding.tvCurrentTime.text = formatTime(position)
            binding.tvTotalTime.text = formatTime(duration)
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return "$minutes:${secs.toString().padStart(2, '0')}"
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

    // -------------------------------------------------------------------------
    // Settings Dialog
    // -------------------------------------------------------------------------

    private fun showSettingsDialog() {
        pauseVideo()

        val options = arrayOf(
            if (isMuted) "🔊 Unmute Audio" else "🔇 Mute Audio",
            "⏩ Playback Speed (current: ${playbackSpeed}x)",
            "↩️ Restart Video",
            "⏭ Skip Forward 10s",
            "⏮ Skip Back 10s",
            "🔆 Screen stays ON (active)",
            "❌ Close Video"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("VR Player Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleMute()
                    1 -> showSpeedDialog()
                    2 -> restartVideo()
                    3 -> skipForward()
                    4 -> skipBack()
                    5 -> Toast.makeText(this, "Screen will stay on while playing ✓", Toast.LENGTH_SHORT).show()
                    6 -> {
                        stopAndReset()
                        showHomeScreen()
                    }
                }
            }
            .setOnDismissListener {
                if (!isPlaying) playVideo()
            }
            .show()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val vol = if (isMuted) 0f else 1f
        playerLeft?.setVolume(vol, vol)
        Toast.makeText(this, if (isMuted) "Muted 🔇" else "Unmuted 🔊", Toast.LENGTH_SHORT).show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                playbackSpeed = values[which]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playerLeft?.playbackParams = playerLeft?.playbackParams?.setSpeed(playbackSpeed)!!
                    playerRight?.playbackParams = playerRight?.playbackParams?.setSpeed(playbackSpeed)!!
                }
                Toast.makeText(this, "Speed: ${speeds[which]}", Toast.LENGTH_SHORT).show()
                playVideo()
            }
            .setOnDismissListener { playVideo() }
            .show()
    }

    private fun restartVideo() {
        playerLeft?.seekTo(0)
        playerRight?.seekTo(0)
        playVideo()
    }

    private fun skipForward() {
        val pos = (playerLeft?.currentPosition ?: 0) + 10000
        playerLeft?.seekTo(pos)
        playerRight?.seekTo(pos)
        playVideo()
    }

    private fun skipBack() {
        val pos = maxOf(0, (playerLeft?.currentPosition ?: 0) - 10000)
        playerLeft?.seekTo(pos)
        playerRight?.seekTo(pos)
        playVideo()
    }

    // -------------------------------------------------------------------------
    // Fullscreen
    // -------------------------------------------------------------------------

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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
