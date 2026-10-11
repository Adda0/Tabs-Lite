package com.gbros.tabslite.view.taborigin

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gbros.tabslite.R
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.data.tab.TabOrigin
import com.gbros.tabslite.data.tab.origin

/** Where a tab comes from, as shown to the user: "Local file", or the site's name */
@Composable
fun tabOriginLabel(origin: TabOrigin): String = when (origin) {
    TabOrigin.LocalFile -> stringResource(R.string.tab_origin_local_file)
    TabOrigin.UltimateGuitar -> "ultimate-guitar.com"
    is TabOrigin.Songbook -> origin.source.siteName
}

/**
 * The name of a version of a song in a version list: its number on Ultimate Guitar, else where it
 * comes from, numbered when that page holds several versions (`plnyzpevnik.cz 2`)
 */
@Composable
fun tabVersionLabel(tab: ITab): String = when (val origin = tab.origin) {
    TabOrigin.UltimateGuitar -> stringResource(R.string.tab_version_number, tab.version)
    else -> tabOriginLabel(origin) + if (tab.version > 0) " ${tab.version}" else ""
}
