package com.cramsan.cmpbridge.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Number of rows in the scrollable list — deliberately more than fit on screen at once. */
internal const val ITEM_COUNT = 40

/**
 * Demo screen for cmp-bridge: one composable, shared verbatim between the desktop and wasmJs
 * entry points, so [DemoScenarioTest] proves BridgeDriver drives the *same* UI on both platforms
 * rather than two independently-built screens that merely look similar.
 *
 * Every interactive/readable element carries a stable [Modifier.testTag] — the only thing either
 * platform's bridge (the real semantics tree on desktop, the real accessibility DOM on web) needs
 * to find it. Uses [BasicTextField] rather than material3's `TextField` — both were tried, and
 * both hit the same Compose Multiplatform 1.10.3 web-target gap noted in [DemoScenarioTest]
 * (a text-input element, and whatever Column sibling follows it, permanently report zero bounds
 * in the web accessibility DOM); `BasicTextField` is kept as the simpler of the two either way.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface {
            var count by remember { mutableStateOf(0) }
            var name by remember { mutableStateOf("") }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Count: $count",
                    modifier = Modifier.testTag("counter_text"),
                )
                Button(
                    onClick = { count++ },
                    modifier = Modifier.testTag("increment_button"),
                ) {
                    Text("Increment")
                }

                Spacer(modifier = Modifier.height(24.dp))

                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .testTag("name_field")
                        .background(Color.White)
                        .padding(8.dp),
                )
                Text(
                    text = if (name.isBlank()) "Hello, stranger!" else "Hello, $name!",
                    modifier = Modifier.testTag("greeting_text"),
                )

                Spacer(modifier = Modifier.height(24.dp))

                LazyColumn(
                    modifier = Modifier.testTag("item_list").height(200.dp),
                ) {
                    items(ITEM_COUNT) { index ->
                        Text(
                            text = "Item #$index",
                            modifier = Modifier.testTag("item_$index").padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
