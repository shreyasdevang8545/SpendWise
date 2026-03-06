# SMS Receiver Debugging Guide

## Issue: SMS Intent Not Being Received

If `SmsReceiver.onReceive()` is not being called when an SMS arrives, follow these steps:

---

## 1. **Verify Manifest Declaration** ✓
The manifest is correctly configured in `AndroidManifest.xml`:
- ✅ `android:exported="true"` - Allows system to deliver SMS intents
- ✅ `android:permission="android.permission.BROADCAST_SMS"` - Restricts to system broadcaster
- ✅ Priority set to 999 - Ensures early processing
- ✅ Intent filter registered for `android.provider.Telephony.SMS_RECEIVED`

---

## 2. **Runtime Permissions (CRITICAL for Android 6.0+)** ⭐
**This is likely your issue!**

### Added in MainActivity:
A permission request launcher has been added that requests:
- `android.permission.RECEIVE_SMS`
- `android.permission.READ_SMS`

**What you need to do:**
1. **Run the app on your device**
2. **Allow permissions** when prompted
3. **Check adb logcat:**
   ```bash
   adb logcat | grep "SmsReceiver\|MainActivity"
   ```
   Look for confirmation messages like:
   - "All SMS permissions granted!"
   - "SMS permissions already granted."

---

## 3. **Verify Using ADB**

### Check if permissions are granted:
```bash
adb shell pm list permissions -g | grep "android.permission.RECEIVE_SMS"
adb shell pm list permissions -g | grep "android.permission.READ_SMS"
```

### Check if your app is granted those permissions:
```bash
adb shell pm list permissions -u | grep "com.tech.spendwise"
```

### Manually grant permissions (for testing):
```bash
adb shell pm grant com.tech.spendwise android.permission.RECEIVE_SMS
adb shell pm grant com.tech.spendwise android.permission.READ_SMS
```

---

## 4. **Monitor Logs in Real-time**

Open Android Studio Logcat and filter for:
```
tag:SmsReceiver OR tag:MainActivity
```

You should see:
- **First time app opens:**
  ```
  I/MainActivity: Requesting SMS permissions: [android.permission.RECEIVE_SMS, android.permission.READ_SMS]
  ```
- **After granting permissions:**
  ```
  I/MainActivity: All SMS permissions granted!
  ```
- **When SMS arrives:**
  ```
  V/SmsReceiver: onReceive() called. Action: android.provider.Telephony.SMS_RECEIVED
  D/SmsReceiver: SMS_RECEIVED intent detected!
  D/SmsReceiver: PDU Format: 3gpp
  D/SmsReceiver: Found 1 PDU(s)
  D/SmsReceiver: Received SMS: [actual message text]
  I/SmsReceiver: Parsed transaction JSON: {...}
  ```

---

## 5. **Test SMS Reception**

### Using emulator:
```bash
# Open Android Studio and send a test SMS via emulator controls
```

### Using real device with adb:
```bash
adb shell am broadcast -a android.provider.Telephony.SMS_RECEIVED \
  --ei slot 0 \
  -n com.tech.spendwise/.SmsReceiver
```

Or simulate with a real SMS (requires a SIM with SMS service).

---

## 6. **Common Issues & Fixes**

| Issue | Cause | Solution |
|-------|-------|----------|
| `onReceive()` never called | Permissions not granted | Grant permissions in Settings → Apps → SpendWise |
| `onReceive()` called but logs show "extras bundle is null" | Invalid broadcast data | Ensure real SMS arrives (not test broadcast) |
| "PDUs not found in SMS bundle" | Bundle key mismatch | Verify PDU_KEY = "pdus" (lowercase) |
| App crashes in `onReceive()` | Exception thrown | Check logcat for full stack trace |

---

## 7. **Additional Checks**

### Verify BroadcastReceiver is properly registered:
```bash
adb shell dumpsys package receivers | grep -A 5 "SmsReceiver"
```

Should output something like:
```
com.tech.spendwise/.SmsReceiver:
    android.provider.Telephony.SMS_RECEIVED filter...
    exported=true permission=android.permission.BROADCAST_SMS
```

### Check if another app is consuming the broadcast:
```bash
adb shell dumpsys package resolvers | grep SMS_RECEIVED
```

If another SMS app is absorbing the broadcast, it may prevent ours from receiving it.

---

## 8. **Next Steps**

1. ✅ Updated `SmsReceiver.kt` with comprehensive logging
2. ✅ Updated `MainActivity.kt` with runtime permission handling
3. **Action required:** Run the app and grant SMS permissions
4. **Monitor logs** while sending/receiving an SMS

Once permissions are granted, you should see detailed logs showing:
- Intent arrival
- PDU extraction
- SMS body reconstruction
- JSON parsing

---

## 9. **Still Not Working?**

If SMS still doesn't arrive after granting permissions:

1. **Force stop and reinstall:**
   ```bash
   adb uninstall com.tech.spendwise
   adb install app-debug.apk
   ```

2. **Enable ADB verbosity:**
   ```bash
   adb logcat -v threadtime | grep -i sms
   ```

3. **Send SMS and watch the logs** - you should see entries from `SmsReceiver` tag

4. **Check for competing receivers:**
   - Disable default SMS app temporarily
   - Disable other banking apps that might have SMS receivers

---

## Summary

**The most likely issue:** Missing runtime permissions (Android 6.0+).

**Fixes applied:**
1. ✅ Enhanced `SmsReceiver` with detailed logging at every step
2. ✅ Added `MainActivity` permission request workflow
3. ✅ Manifest configuration verified

**Next action:** Launch app → Grant permissions → Send SMS → Check logs

