package com.zjr.hesimusic.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SCAN_FOLDERS = "scan_folders"
        private const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
    }

    fun saveScanFolders(folders: Set<String>) {
        prefs.edit().putStringSet(KEY_SCAN_FOLDERS, folders).apply()
    }

    fun getScanFolders(): Set<String> {
        return prefs.getStringSet(KEY_SCAN_FOLDERS, emptySet()) ?: emptySet()
    }

    fun saveExcludedFolders(folders: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, folders).apply()
    }

    fun getExcludedFolders(): Set<String> {
        return prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet()) ?: emptySet()
    }

    fun addScanFolder(folder: String) {
        val current = getScanFolders().toMutableSet()
        current.add(folder)
        saveScanFolders(current)
    }

    fun removeScanFolder(folder: String) {
        val current = getScanFolders().toMutableSet()
        current.remove(folder)
        saveScanFolders(current)
    }

    fun addExcludedFolder(folder: String) {
        val current = getExcludedFolders().toMutableSet()
        current.add(folder)
        saveExcludedFolders(current)
    }

    fun removeExcludedFolder(folder: String) {
        val current = getExcludedFolders().toMutableSet()
        current.remove(folder)
        saveExcludedFolders(current)
    }
}
