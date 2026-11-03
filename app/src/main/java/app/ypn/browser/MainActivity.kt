package app.ypn.browser

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ypn.browser.ui.YpnApp
import app.ypn.browser.ui.theme.YpnBrowserTheme
import app.ypn.browser.ui.theme.YpnThemeId

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ypn-browser", Context.MODE_PRIVATE)
        setContent {
            // Theme choice lives on the phone only (same pattern as Textoo).
            var themeId by remember {
                mutableStateOf(YpnThemeId.fromId(prefs.getString("theme", null)))
            }
            YpnBrowserTheme(themeId = themeId) {
                YpnApp(
                    themeId = themeId,
                    onThemeChange = { chosen ->
                        themeId = chosen
                        prefs.edit().putString("theme", chosen.id).apply()
                    }
                )
            }
        }
    }
}
