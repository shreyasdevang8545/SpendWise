package com.tech.spendwise

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MaintenanceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance)

        val message = intent.getStringExtra("maintenance_message")
        if (!message.isNullOrEmpty()) {
            findViewById<TextView>(R.id.textMaintenanceMessage).text = message
        }
    }

    override fun onBackPressed() {
        // Prevent going back to the app during maintenance
        super.onBackPressed()
        finishAffinity()
    }
}
