# picoSMS Privacy Policy

_Last updated: October 10, 2026_

picoSMS ("the app") is an open-source SMS app developed by vladgba. This policy explains what the app does with your data. In short: **everything stays on your device.**

## No data collection

- The app does **not** request the `INTERNET` permission, so it has no way to send anything off your device.
- It contains no analytics, crash reporting, advertising or tracking SDKs, and no third-party libraries at all.
- The developer receives no data from the app, whether personal, usage or diagnostic.

## Data the app accesses on your device

| Data | Why | Where it stays |
|---|---|---|
| SMS messages (read, send, receive, back up, restore) | This is the app's core function as your default SMS app | The Android system message store on your device, plus backup files you choose to create |
| Contacts (read only, optional) | To show names instead of numbers, to let you pick a recipient, and for the optional "always allow contacts" filter setting | Read on demand; names are kept in memory only while the app runs |
| Notifications (optional) | To alert you to new messages | On your device |

## Data the app stores itself

The app keeps a small private database inside its own storage area on your device. It holds:

- **Filter rules** you create (numbers, sender names or text patterns).
- **Blocked messages**, meaning messages stopped by your rules. They stay here until you restore or delete them.
- **Quick pick regexes** you create.
- **Labels** you create (names, sender patterns and words).
- **Settings**: whitelist mode, "always allow contacts" and "pick OTP codes".

This data is never transmitted. It is excluded from Android cloud backup and device-to-device transfer. Uninstalling the app deletes it.

## Message backups

When you choose **Back up messages**, the app writes your SMS to a file at a location you pick. It does this only when you ask. Nothing is scheduled, nothing is uploaded by the app, and the app still has no internet access.

- **Without a password,** the file is plain text. Anyone who gets it can read your messages, including one-time codes.
- **With a password,** the file is encrypted with AES-256-GCM, using a key derived from your password with PBKDF2. The password is never stored. If you forget it, the backup cannot be recovered by anyone, including the developer.
- **If you save the file to a cloud folder,** that service's own privacy terms apply to the file.

**Restore messages** reads a backup file you pick and adds its messages to your phone's message store. The file is never sent anywhere.

## Settings export

When you choose **Export settings**, the app writes your rules, labels, Quick pick regexes and switches to a file at a location you pick. Rules and labels can contain phone numbers and words you entered, so treat the file like any personal document. The app never exports messages, and it never uploads anything. The file goes only where you put it.

## Clipboard

When you tap a "Copy" button (for example on a one-time code), the app puts that value on the system clipboard. It never reads the clipboard. One-time codes are marked as sensitive, so Android 13+ hides them in the clipboard preview.

## Sending messages

SMS messages you send go through your mobile carrier, like any SMS. Your carrier's own privacy terms cover that delivery. The app adds nothing to your messages.

## Permissions

- `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`: send, receive and display SMS. The app needs these as the default SMS app.
- `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`: Android requires these for the default SMS role. MMS content isn't processed; the app only shows a notification that an MMS arrived.
- `READ_CONTACTS` (optional): show contact names and support the contact filter.
- `POST_NOTIFICATIONS` (optional): show new-message notifications.

## Children

The app doesn't knowingly collect any information from anyone, including children under 13.

## Changes

If this policy changes, the updated version will be published in the app's repository with a new "Last updated" date.

## Contact

Questions? Open an issue at https://github.com/vladgba/picoSMS.
