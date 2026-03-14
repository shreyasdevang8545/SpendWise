package com.tech.spendwise.utils

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.tech.spendwise.R

object UIUtils {

    fun showSuccessSnackbar(view: View, message: String) {
        Snackbar.make(view, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(view.context, R.color.primary_green))
            .show()
    }

    fun showErrorSnackbar(view: View, message: String) {
        Snackbar.make(view, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(view.context, android.R.color.holo_red_dark))
            .show()
    }

    fun showInfoSnackbar(view: View, message: String) {
        Snackbar.make(view, message, Snackbar.LENGTH_SHORT)
            .show()
    }

    fun showActionSnackbar(
        view: View,
        message: String,
        actionText: String,
        duration: Int = Snackbar.LENGTH_LONG,
        onActionClick: () -> Unit
    ) {
        Snackbar.make(view, message, duration)
            .setAction(actionText) { onActionClick() }
            .show()
    }

    fun showAlertDialog(
        context: android.content.Context,
        title: String,
        message: String,
        positiveButtonText: String = "OK",
        negativeButtonText: String? = "Cancel",
        onPositiveClick: (() -> Unit)? = null
    ) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onPositiveClick?.invoke() }
        
        if (negativeButtonText != null) {
            builder.setNegativeButton(negativeButtonText, null)
        }
        
        builder.show()
    }

    fun showListDialog(
        context: android.content.Context,
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onItemClick(which) }
            .show()
    }
}
