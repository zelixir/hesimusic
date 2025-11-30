package com.zjr.hesimusic.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FastScrollbar(
    sections: List<Char>,
    onSectionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var componentHeight by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(40.dp)
            .padding(vertical = 8.dp)
            .onGloballyPositioned { coordinates ->
                componentHeight = coordinates.size.height.toFloat()
            }
            .pointerInput(sections) {
                detectTapGestures(
                    onTap = { offset ->
                        val index = (offset.y / componentHeight * sections.size).toInt()
                        val coercedIndex = index.coerceIn(0, sections.lastIndex)
                        onSectionSelected(coercedIndex)
                        selectedIndex = coercedIndex
                        isDragging = false // Ensure bubble doesn't show or stays hidden
                    },
                    onPress = {
                        // Optional: visual feedback on press
                        isDragging = true // Show bubble on press?
                        val index = (it.y / componentHeight * sections.size).toInt()
                        val coercedIndex = index.coerceIn(0, sections.lastIndex)
                        selectedIndex = coercedIndex
                        tryAwaitRelease()
                        isDragging = false
                        selectedIndex = -1
                    }
                )
            }
            .pointerInput(sections) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val index = (offset.y / componentHeight * sections.size).toInt()
                        val coercedIndex = index.coerceIn(0, sections.lastIndex)
                        if (coercedIndex != selectedIndex) {
                            selectedIndex = coercedIndex
                            onSectionSelected(coercedIndex)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        selectedIndex = -1
                    },
                    onDragCancel = {
                        isDragging = false
                        selectedIndex = -1
                    },
                    onVerticalDrag = { change, _ ->
                        val index = (change.position.y / componentHeight * sections.size).toInt()
                        val coercedIndex = index.coerceIn(0, sections.lastIndex)
                        if (coercedIndex != selectedIndex) {
                            selectedIndex = coercedIndex
                            onSectionSelected(coercedIndex)
                        }
                    }
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        sections.forEachIndexed { index, char ->
            Text(
                text = char.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
    
    // Optional: Bubble indicator
    if (isDragging && selectedIndex in sections.indices) {
        Box(
            modifier = Modifier
                .padding(end = 40.dp) // Offset to the left of the bar
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(16.dp)
        ) {
            Text(
                text = sections[selectedIndex].toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}
