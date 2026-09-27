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
}
