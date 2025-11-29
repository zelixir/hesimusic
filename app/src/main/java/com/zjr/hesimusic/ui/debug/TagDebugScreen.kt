package com.zjr.hesimusic.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.data.scanner.TagDebugger

@Composable
fun TagDebugScreen(
    viewModel: TagDebugViewModel = hiltViewModel()
) {
    var filePath by remember { mutableStateOf("/storage/emulated/0/Music/02. destiny.mp3") }
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = filePath,
            onValueChange = { filePath = it },
            label = { Text("File Path") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = { viewModel.debugFile(filePath) },
            modifier = Modifier.fillMaxWidth(),
            enabled = filePath.isNotBlank()
        ) {
            Text("Analyze Tag")
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = uiState) {
            is TagDebugUiState.Loading -> CircularProgressIndicator()
            is TagDebugUiState.Success -> {
                if (state.result.error != null) {
                    Text("Error: ${state.result.error}", color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn {
                        items(state.result.fields) { field ->
                            FieldDebugCard(field)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
            TagDebugUiState.Idle -> Text("Enter a file path to start debugging")
        }
    }
}

@Composable
fun FieldDebugCard(info: TagDebugger.FieldDebugInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Field: ${info.fieldName}", style = MaterialTheme.typography.titleMedium)
            Text("Original: ${info.originalValue}")
            Text("Hex: ${info.rawBytesHex}", style = MaterialTheme.typography.bodySmall)
            Text("Detected: ${info.detectedCharset ?: "Unknown"}", color = MaterialTheme.colorScheme.primary)
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            
            info.decodedCandidates.forEach { (charset, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(charset, style = MaterialTheme.typography.labelSmall)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
