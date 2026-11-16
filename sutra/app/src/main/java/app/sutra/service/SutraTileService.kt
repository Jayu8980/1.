package app.sutra.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.sutra.data.SutraStore
import app.sutra.ui.MainActivity

/** Quick Settings tile: one tap starts or closes the background service. */
class SutraTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        SutraStore.init(this)
        render()
    }

    override fun onClick() {
        super.onClick()
        SutraStore.init(this)
        if (SutraStore.running.value || SutraStore.serviceWanted) {
            SutraService.stop(this)
        } else {
            try {
                SutraService.start(this)
            } catch (e: Exception) {
                // Some Android versions refuse an FGS start from a tile; open the app instead.
                openApp()
            }
        }
        render(forceOn = SutraStore.serviceWanted)
    }

    private fun render(forceOn: Boolean? = null) {
        val tile = qsTile ?: return
        val on = forceOn ?: (SutraStore.running.value || SutraStore.serviceWanted)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
