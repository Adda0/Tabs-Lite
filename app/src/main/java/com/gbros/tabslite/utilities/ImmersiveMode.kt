package com.gbros.tabslite.utilities

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hide the system status bar while this view is shown, giving the screen its full height.
 * The bar is shown again when leaving. Swiping from the top edge still reveals it temporarily.
 */
@Composable
fun ImmersiveMode() {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(Unit) {
        val insetsController = (context as? Activity)?.let {
            WindowCompat.getInsetsController(it.window, view)
        }
        insetsController?.hide(WindowInsetsCompat.Type.statusBars())

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
