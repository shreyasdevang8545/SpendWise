package com.tech.spendwise

/**
 * Simple singleton to manage app lock state.
 * Tracks whether the app is currently unlocked and when it was last backgrounded.
 */
object SpendWiseLockManager {
    private var isUnlocked = false
    private var lastBackgroundedTime = 0L
    private const val LOCK_TIMEOUT_MS = 30_000L // 30 seconds

    fun onUnlocked() {
        isUnlocked = true
    }

    fun onAppBackgrounded() {
        lastBackgroundedTime = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        if (isUnlocked && lastBackgroundedTime > 0) {
            val elapsed = System.currentTimeMillis() - lastBackgroundedTime
            if (elapsed > LOCK_TIMEOUT_MS) {
                isUnlocked = false
            }
        }
    }

    fun shouldShowLockScreen(): Boolean {
        return !isUnlocked
    }

    fun reset() {
        isUnlocked = false
        lastBackgroundedTime = 0L
    }
}
