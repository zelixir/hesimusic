# Frontend Architecture

This document describes the refactored frontend architecture.

## Directory Structure

```
ui/src/
├── components/        # Vue components
│   ├── Modal.vue     # Reusable modal component
│   ├── FolderPicker.vue
│   ├── ScanPage.vue
│   ├── SongLibrary.vue
│   ├── TrackList.vue
│   └── ...
├── composables/      # Reusable Vue composition functions
│   ├── useNavigation.ts       # App navigation logic
│   ├── useScanSettings.ts     # Scan settings management
│   ├── useFolderPicker.ts     # Folder picker state management
│   └── index.ts               # Barrel export
├── services/         # Service layer for API calls
│   ├── musicApi.ts
│   ├── scanApi.ts
│   ├── musicBridge.ts
│   └── errorService.ts
├── types/            # Shared TypeScript types
│   └── index.ts
├── utils/            # Utility functions
└── App.vue           # Main app component
```

## Key Improvements

### 1. Centralized Type Definitions

All shared types are now in `types/index.ts`:
- `Track` - Music track representation
- `FolderSelection` - Selected folder data
- `ScanSettings` - Scan configuration
- `ScanProgress` - Scan progress data
- `ErrorEntry` - Error information
- `ViewType` - App view types

### 2. Composables for Reusable Logic

**useNavigation**
- Manages app navigation state
- Provides methods to navigate between views
- Centralizes navigation logic

**useScanSettings**
- Manages scan settings state (folders, excludes, options)
- Handles loading/saving settings
- Provides methods to add/remove folders and excludes

**useFolderPicker**
- Manages folder picker state
- Handles folder navigation and selection
- Separates UI from business logic

### 3. Reusable Components

**Modal**
- Generic modal component with configurable size
- Supports header, content, and footer slots
- Handles backdrop clicks and close events
- Used by FolderPicker, PermissionModal, and ScanProgress

### 4. Separation of Concerns

- Components focus on presentation
- Composables handle business logic
- Services handle API communication
- Types ensure type safety across the app

## Usage Examples

### Using a Composable

```typescript
import { useScanSettings } from '@/composables'

const {
  folders,
  excludes,
  skipShort,
  addFolder,
  removeFolder,
  saveCurrentSettings
} = useScanSettings()
```

### Using the Modal Component

```vue
<modal :visible="showModal" title="My Title" @close="showModal = false">
  <p>Modal content</p>
  <template #footer>
    <button @click="handleSave">Save</button>
  </template>
</modal>
```

## Benefits

1. **Better Code Reusability** - Logic can be shared across components
2. **Improved Testability** - Composables can be tested independently
3. **Type Safety** - Centralized types prevent inconsistencies
4. **Easier Maintenance** - Clear separation of concerns
5. **Smaller Components** - Components are focused and easier to understand
