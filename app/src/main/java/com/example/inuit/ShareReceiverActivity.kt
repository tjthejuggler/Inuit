package com.example.inuit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * UI-less trampoline for the share sheet: receives ACTION_SEND text/plain,
 * drops it into the shared-snippets pool, shows a toast and finishes —
 * WITHOUT ever taking the user away from the app they shared from.
 * (Theme.NoDisplay means no window is ever shown.)
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null
        val graph = (application as InuitApp).graph
        val added = text != null && graph.sharedTexts.add(text)
        val msg = when {
            text == null -> "Nothing shareable in that content"
            added -> "Saved to Inuit — weight \"Shared snippets\" in a net's question mix to use it"
            else -> "Already in Inuit's shared snippets"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
