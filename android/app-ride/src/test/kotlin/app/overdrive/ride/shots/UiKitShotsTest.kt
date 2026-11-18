package app.overdrive.ride.shots

import app.overdrive.uikit.preview.Backdrop
import app.overdrive.uikit.preview.ChromeSample
import app.overdrive.uikit.preview.ComponentSheet
import app.overdrive.uikit.preview.IconSheet
import app.overdrive.uikit.preview.ListSample
import app.overdrive.uikit.preview.OverlayOverVideo
import app.overdrive.uikit.preview.OverlayStatesSheet
import app.overdrive.uikit.preview.ProfileSample
import app.overdrive.uikit.preview.ZoneBarsSample
import org.junit.Test

/** The design system's components and the overlay, rendered from the ui-kit sample sheets. */
class UiKitShotsTest : ShotTest() {
    @Test fun components() = shot("kit--components") { ComponentSheet() }

    @Test fun icons() = shot("kit--icons") { IconSheet() }

    @Test fun profile() = shot("kit--profile") { ProfileSample() }

    @Test fun zoneBars() = shot("kit--zone-bars") { ZoneBarsSample() }

    @Test fun list() = shot("kit--list") { ListSample() }

    @Test fun chrome() = shot("kit--chrome") { ChromeSample() }

    @Test fun overlayStates() = shot("overlay--states") { OverlayStatesSheet() }

    @Test fun overlayDark() = shot("overlay--dark") { OverlayOverVideo(Backdrop.Dark) }

    @Test fun overlayBright() = shot("overlay--bright") { OverlayOverVideo(Backdrop.Bright) }

    @Test fun overlayBusy() = shot("overlay--busy") { OverlayOverVideo(Backdrop.Busy) }
}
