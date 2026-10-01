package com.pranav.atminigame

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {

    private val aiController = AiController(this, "gemma3-270m-it-q8.task")
    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        // Honor system bars using WindowCompat
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        gameView = GameView(this, aiController)
        setContentView(gameView)
    }

    override fun onDestroy() {
        super.onDestroy()
        aiController.close()
    }

    override fun onPause() {
        if (::gameView.isInitialized) gameView.onHostPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::gameView.isInitialized) gameView.onHostResumed()
    }

    @Deprecated("Deprecated in Android; still routed here for compatibility with minSdk 23")
    override fun onBackPressed() {
        if (!::gameView.isInitialized || !gameView.handleBackPressed()) super.onBackPressed()
    }
}
