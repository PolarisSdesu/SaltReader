package moe.gbxin.saltreader.ui

import androidx.activity.ComponentActivity

/**
 * Activities extend this so they can be swapped to a different base later; language changes are
 * handled live by [moe.gbxin.saltreader.ui.theme.SaltReaderTheme] without recreating them.
 */
abstract class LocalizedActivity : ComponentActivity()
