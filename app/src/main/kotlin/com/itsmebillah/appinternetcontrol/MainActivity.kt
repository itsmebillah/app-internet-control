package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.launch_message)
                textSize = 22f
                gravity = Gravity.CENTER
            },
        )
    }
}
