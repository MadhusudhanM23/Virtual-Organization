/**
 * English message catalog (the only locale for now). Every user-visible string goes through `t(id)`; nothing is
 * hard-coded in components. To add a language, register another full catalog in `index.ts`.
 */
export const messagesEn = {
  'app.name': 'Virtual Org Operator',
  'app.skipToContent': 'Skip to main content',
  'app.loading': 'Loading',

  // Connectivity and sync status
  'net.online': 'Online',
  'net.offline': 'Offline',
  'net.offlineBanner':
    'You are offline. Your work is saved on this device and is sent when the connection returns.',
  'net.onlineSignedOutBanner':
    'You are online but not signed in. Sign in to send your saved work. Nothing is uploaded while signed out.',
  'sync.pending': '{count} pending',
  'sync.allSynced': 'All synced',
  'sync.needsAttention': '{count} need attention',
  'sync.now': 'Sync now',
  'sync.running': 'Syncing',
  'sync.lastRun': 'Last sync: {time}',

  // Truth labels (BLUEPRINT section 22, ADR-27)
  'truth.noData': 'NO OPERATIONAL DATA SHOWN',
  'truth.noData.aria': 'No operational data is shown on this screen',
  'truth.local': 'LOCAL DEVICE QUEUE - NOT YET ON THE SERVER',
  'truth.local.aria': 'Items on this screen are stored on this device and are not yet confirmed by the server',
  'truth.mode.LIVE': 'LIVE',
  'truth.mode.SIMULATED': 'SIMULATED',
  'truth.mode.SYNTHETIC': 'SYNTHETIC',
  'truth.mode.REPLAY': 'REPLAY',
  'truth.mode.MIXED': 'MIXED',
  'truth.mode.aria': 'Data mode: {mode}',
  'truth.cached': 'cached {time}',

  // Navigation
  'nav.tasks': 'My tasks',
  'nav.queue': 'Sync queue',
  'nav.urgent': 'Urgent',
  'nav.label': 'Main',
  'nav.signOut': 'Sign out',

  // Login
  'login.title': 'Sign in',
  'login.tenant': 'Organization',
  'login.email': 'Email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.submitting': 'Signing in',
  'login.error': 'Sign in failed. Check your organization, email and password.',
  'login.offline': 'You are offline. Sign-in needs a connection.',
  'login.required': 'Enter your organization, email and password.',
  'login.pendingNotice':
    'This device holds {count} saved item(s). They are sent only after the same user signs in.',

  // Tasks
  'tasks.title': 'My tasks',
  'tasks.site': 'Site',
  'tasks.refresh': 'Refresh',
  'tasks.refreshing': 'Refreshing',
  'tasks.empty': 'No tasks are assigned to you.',
  'tasks.cachedOnly': 'Showing tasks saved on this device. Last refreshed {time}. They may be out of date.',
  'tasks.neverLoaded': 'No tasks are saved on this device yet. Connect once to load them.',
  'tasks.loadError': 'Tasks could not be loaded.',
  'tasks.statusPending': 'PENDING_SYNC',
  'tasks.from': 'From',
  'tasks.to': 'To',
  'tasks.priority': 'Priority {n}',
  'tasks.lastRefreshed': 'Refreshed {time}',
  'tasks.open': 'Open',
  'tasks.back': 'Back to tasks',
  'tasks.notFound': 'This task is not on this device.',
  'tasks.staleBanner':
    'This task was reassigned or a saved action is stale. The server assignment wins. Refresh before continuing.',
  'tasks.conflictBanner': 'A saved action was not applied. See the sync queue for details.',
  'tasks.reassignedNow': 'This task is no longer assigned to you. Your saved actions for it are marked stale.',
  'tasks.type.PICK': 'Pick',
  'tasks.type.PUTAWAY': 'Put-away',
  'tasks.type.DOCK': 'Dock',
  'tasks.status.ASSIGNED': 'Assigned',
  'tasks.status.ACCEPTED': 'Accepted',
  'tasks.status.STARTED': 'Started',
  'tasks.status.PAUSED': 'Paused',
  'tasks.status.COMPLETED': 'Completed',
  'tasks.status.FAILED': 'Failed',
  'tasks.status.CANCELLED': 'Cancelled',
  'tasks.action.accept': 'Accept',
  'tasks.action.start': 'Start',
  'tasks.action.pause': 'Pause',
  'tasks.action.resume': 'Resume',
  'tasks.action.complete': 'Complete task',
  'tasks.action.fail': 'Cannot do it',
  'tasks.action.queued': 'Saved on this device. It will be sent when online and signed in. Status: PENDING_SYNC.',
  'tasks.fail.reason': 'Why can the task not be done?',
  'tasks.fail.confirm': 'Save as failed',
  'tasks.actions': 'Actions',
  'tasks.queuedForTask': 'Saved for this task',

  // Report issue
  'issue.title': 'Report an issue',
  'issue.type': 'Issue type',
  'issue.description': 'What happened?',
  'issue.submit': 'Save issue',
  'issue.saved': 'Issue saved on this device (PENDING_SYNC).',
  'issue.type.BLOCKED': 'Blocked',
  'issue.type.DAMAGED_GOODS': 'Damaged goods',
  'issue.type.WRONG_LOCATION': 'Wrong location',
  'issue.type.EQUIPMENT_FAULT': 'Equipment fault',
  'issue.type.SAFETY_CONCERN': 'Safety concern',
  'issue.type.OTHER': 'Other',
  'issue.safetyNote':
    'A safety concern here is a report only. It does not stop any machine. For danger, follow your site safety procedure.',

  // Photo evidence
  'photo.title': 'Photo evidence',
  'photo.choose': 'Take or choose a photo',
  'photo.note': 'Note (optional)',
  'photo.privacy':
    'Privacy: location (GPS) and other hidden photo data are removed on this device, the photo is shrunk to at most {maxSide} pixels, and it is sent only while you are signed in.',
  'photo.preparing': 'Preparing photo',
  'photo.saved': 'Photo saved on this device (PENDING_SYNC). It is uploaded when you are online and signed in.',
  'photo.error.unsupported': 'Only JPEG, PNG or WebP photos can be used.',
  'photo.error.tooLarge': 'The photo is too large even after shrinking. Choose another.',
  'photo.error.generic': 'The photo could not be prepared.',
  'photo.preview': 'Preview of the photo to be saved',
  'photo.save': 'Save photo to the queue',
  'photo.size': '{kb} KB',

  // Inventory scan
  'scan.title': 'Inventory scan',
  'scan.sku': 'SKU',
  'scan.location': 'Location code',
  'scan.quantity': 'Counted quantity',
  'scan.version': 'Inventory version you scanned against',
  'scan.submit': 'Save scan',
  'scan.saved': 'Scan saved on this device (PENDING_SYNC).',
  'scan.note':
    'The system quantity is never overwritten by a scan. If the inventory changed meanwhile, an inventory exception is filed with your evidence.',

  // Urgent
  'urgent.title': 'Urgent record',
  'urgent.lead':
    'Create an urgent local record. It is saved on this device and sent for people to see. It is advisory only: it does not stop or start anything.',
  'urgent.category': 'Category',
  'urgent.category.EMERGENCY': 'Emergency',
  'urgent.category.SAFETY': 'Safety',
  'urgent.category.EQUIPMENT': 'Equipment',
  'urgent.category.OTHER': 'Other',
  'urgent.message': 'What is happening?',
  'urgent.lastKnown': 'Last known instruction (optional)',
  'urgent.submit': 'Save urgent record',
  'urgent.saved': 'Urgent record saved on this device. Status: PENDING_SYNC. Nothing was executed.',
  'urgent.callFirst': 'If anyone is in danger, use your site emergency procedure and radio first.',

  // Limits
  'limits.title': 'What does not work offline',
  'limits.approvals': 'Approvals are not available offline. Consequential actions can only be approved online.',
  'limits.estop':
    'Emergency-stop execution is not available offline and is never promised. An urgent record is advisory only.',
  'limits.refused': 'This device will refuse to queue or send approvals and emergency-stop execution.',

  // Queue
  'queue.title': 'Sync queue',
  'queue.empty': 'Nothing is waiting to sync.',
  'queue.retry': 'Retry',
  'queue.discard': 'Discard',
  'queue.discardConfirm': 'Discard this saved item? It will not be sent.',
  'queue.created': 'Saved {time}',
  'queue.attempts': '{n} attempt(s)',
  'queue.nextTry': 'next try {time}',
  'queue.key': 'Idempotency-Key {key}',
  'queue.owner.other': 'Saved by another user on this device. It is held until that user signs in.',
  'queue.list': 'Saved items',
  'queue.status.PENDING_SYNC': 'PENDING_SYNC',
  'queue.status.SYNCING': 'SYNCING',
  'queue.status.SYNCED': 'SYNCED',
  'queue.status.CONFLICT': 'CONFLICT',
  'queue.status.STALE': 'STALE',
  'queue.status.REJECTED': 'REJECTED',
  'queue.status.REFUSED': 'REFUSED',
  'queue.status.FAILED': 'FAILED',
  'queue.kind.TASK_TRANSITION': 'Task action',
  'queue.kind.TASK_ISSUE': 'Issue',
  'queue.kind.EVIDENCE': 'Photo evidence',
  'queue.kind.INVENTORY_SCAN': 'Inventory scan',
  'queue.kind.URGENT_RECORD': 'Urgent record',
  'queue.clearSynced': 'Clear synced items',

  // Conflicts (BLUEPRINT section 12)
  'conflict.already_terminal':
    'Someone else already finished this task. The first valid server command won, so yours was not applied and was recorded as a conflict.',
  'conflict.task_reassigned':
    'This task was reassigned while you were offline. The server assignment wins. Refresh your tasks before continuing.',
  'conflict.invalid_transition':
    'The task changed on the server and this action is no longer valid. It was not applied.',
  'conflict.inventory_exception':
    'The inventory changed since you scanned. An inventory exception was filed with your evidence. The system quantity was not changed.',
  'conflict.not_permitted': 'The server did not allow this action for your account.',
  'conflict.not_found': 'The task no longer exists on the server.',
  'conflict.invalid': 'The server rejected this item as invalid.',
  'conflict.too_large': 'The photo is larger than the server allows.',
  'conflict.exif_present': 'The photo still had hidden metadata and was refused.',
  'conflict.blocked_by_stale': 'Blocked: an earlier saved action for this task is stale.',
  'conflict.refused_by_policy': 'Refused on this device: approvals and emergency-stop execution are never sent.',
  'conflict.server_error': 'The server could not process this item.',

  // Install / update
  'pwa.updateReady': 'A new version is ready.',
  'pwa.update': 'Update',
  'pwa.offlineReady': 'The app is ready to work offline.',
  'pwa.dismiss': 'Dismiss',

  'common.cancel': 'Cancel',
  'common.required': 'required',
  'common.never': 'never',
} as const;

export type MessageId = keyof typeof messagesEn;
export type MessageCatalog = Record<MessageId, string>;
