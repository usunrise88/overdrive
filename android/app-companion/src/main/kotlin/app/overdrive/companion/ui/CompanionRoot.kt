package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.settings.OnboardingStep
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.BottomNav
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.LocalMotionEnabled
import app.overdrive.uikit.NavItem
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.ScreenHeader
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveMotion

/**
 * The whole companion (design/companion.md §2): the first run until there is a service address and token, then the five tabs with
 * their own back stacks, and the screens pushed over them (Settings, a ride, an activity). The bottom navigation shows on the
 * tabs' roots only.
 */
@Composable
fun CompanionRoot(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        when (val current = settings) {
            null -> {
                Unit
            }

            else -> {
                when {
                    !current.isConfigured -> ConnectScreen(container)

                    // A phone that is connected and has not finished the first run meets the steps of the watch data.
                    current.onboarding == OnboardingStep.Health -> HealthStep(container, hostOf(current.serviceUrl))

                    current.onboarding == OnboardingStep.Background -> BackgroundStep(container)

                    current.onboarding == OnboardingStep.Reading -> ReadingStep(container)

                    else -> Tabs(container)
                }
            }
        }
    }
}

@Composable
private fun Tabs(container: CompanionContainer) {
    val nav = container.nav
    // System back: pops a pushed screen, goes to Today from another tab, leaves the app from Today (design/companion.md §2).
    BackHandler(enabled = nav.route != null || nav.tab != Tab.Today) { nav.back() }
    val route = nav.route
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val motion = LocalMotionEnabled.current
            Crossfade(
                targetState = route ?: nav.tab,
                animationSpec = tween(if (motion) OverdriveMotion.durationStandard else 0),
                label = "screen",
            ) { target ->
                when (target) {
                    is Route.Settings -> SettingsScreen(container)
                    is Route.Licences -> LicencesScreen(container)
                    is Route.Notes -> NotesScreen(container)
                    is Route.RideDetail -> RideDetailScreen(container, target.id)
                    is Route.ActivityDetail -> ActivityScreen(container, target.id)
                    is Route.RepeatDetail -> RepeatDetailScreen(container, target.hash)
                    Tab.Today -> TodayScreen(container)
                    Tab.Fitness -> FitnessScreen(container)
                    Tab.Power -> PowerScreen(container)
                    Tab.Wellness -> WellnessScreen(container)
                    Tab.Rides -> RidesScreen(container)
                }
            }
        }
        if (route == null) {
            BottomNav(
                items = Tab.entries.map { NavItem(it.label, it.icon) },
                selected = nav.tab.ordinal,
                onSelect = { nav.select(Tab.entries[it]) },
                onReselect = { nav.reselect(Tab.entries[it]) },
            )
        }
    }
}

/** The licences of what the companion ships (design/companion.md §4.9): a plain text screen. */
@Composable
fun LicencesScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val texts =
        remember {
            listOf("Geist" to "licenses/geist-OFL.txt", "Lucide" to "licenses/lucide-ISC.txt").map { (name, path) ->
                name to
                    try {
                        context.assets.open(path).bufferedReader().use { it.readText() }
                    } catch (e: java.io.IOException) {
                        ""
                    }
            }
        }
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Licences", { container.nav.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            for ((name, text) in texts) {
                item(key = name) {
                    Column {
                        OdText(name, Modifier.padding(top = 8.dp, bottom = 8.dp), style = OdType.heading)
                        OdText(text, style = OdType.label, color = OverdriveColors.textSecondary)
                    }
                }
            }
        }
    }
}
