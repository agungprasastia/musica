package com.music.musica

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.ui.MainViewModel
import com.music.musica.ui.MusicaAppRoot
import com.music.musica.ui.theme.MusicaTheme

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional notification permission on Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestNotificationsIfNeeded()

        setContent {
            val vm: MainViewModel = viewModel()
            val loadedSettings by vm.loadedSettings.collectAsStateWithLifecycle()

            MusicaTheme(themeMode = loadedSettings?.themeMode ?: ThemeMode.SYSTEM) {
                if (loadedSettings == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    MusicaAppRoot(
                        vm = vm,
                        onOpenEqualizer = ::openSystemEqualizer,
                        onApplyLanguage = ::applyAppLanguage,
                        onShareSong = ::shareSong,
                    )
                }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openSystemEqualizer(audioSessionId: Int) {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.player_equalizer_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAppLanguage(language: AppLanguage) {
        val locales = if (language.tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun shareSong(song: Song) {
        val url = "https://music.youtube.com/watch?v=${song.videoId}"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artistText}\n$url")
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.menu_share)))
    }
}
