package com.gbros.tabslite.utilities

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gbros.tabslite.R

/** The language codes a song file's `language` header accepts, with their UI label strings */
private val LANGUAGE_LABELS = mapOf("cz" to R.string.language_cz, "en" to R.string.language_en)

/** The language code of a `language` header (`cz`, `en`, case-insensitive), or blank for anything else */
fun parseLanguage(value: String): String = value.trim().lowercase().takeIf { it in LANGUAGE_LABELS }.orEmpty()

/** [language]'s name for the UI (`Czech`, `English`), or null when not set or unrecognized */
@Composable
fun languageLabel(language: String): String? = LANGUAGE_LABELS[language]?.let { stringResource(it) }
