# Google Play Store Publishing Checklist

## ✅ Pre-Release Setup (Completed)

### 1. App Configuration
- [x] Updated targetSdkVersion to 34 (latest requirement)
- [x] Version code: 16, Version name: 1.14
- [x] Application ID: edu.wisc.chm.screenomics
- [x] ProGuard enabled with optimized rules
- [x] Network security configuration added

### 2. Permissions & Features
- [x] Camera permissions properly declared with required="false"
- [x] Location permissions configured
- [x] Foreground service permissions set
- [x] USE_EXACT_ALARM permission added for Android 14+

### 3. App Signing
- [x] Keystore generation script created (generate-keystore.bat)
- [x] Signing configuration template created
- [x] Build.gradle configured for release signing
- [x] Security files added to .gitignore

## 📋 Action Items Before Publishing

### 1. Generate Release Keystore
```bash
# Run the batch file to generate your keystore
generate-keystore.bat

# Then create signing.properties from template
copy signing.properties.template signing.properties
# Edit signing.properties with your passwords
```

**IMPORTANT**: 
- Keep keystore file backed up securely
- Never lose the keystore - you'll need it for all updates
- Store passwords in a password manager

### 2. Build Release AAB
```bash
# Clean build
gradlew clean

# Build release AAB (recommended for Play Store)
gradlew bundleRelease

# Or build release APK for testing
gradlew assembleRelease
```

Output location: `app/build/outputs/bundle/release/`

### 3. Test Release Build
- [ ] Install on physical device
- [ ] Test all core features:
  - [ ] Screen recording functionality
  - [ ] Video recording (MindPulse)
  - [ ] Upload service
  - [ ] Location tracking
  - [ ] Notifications
- [ ] Verify no crashes in Firebase Crashlytics
- [ ] Check ProGuard didn't break functionality

### 4. Play Console Setup

#### Store Listing
- [ ] App name: NDScreenomics
- [ ] Short description (80 chars max)
- [ ] Full description (4000 chars max)
- [ ] App category: Medical or Health & Fitness
- [ ] Content rating questionnaire

#### Graphic Assets
Required:
- [ ] App icon: 512x512 PNG (already have: ic_launcher_leaf_text)
- [ ] Feature graphic: 1024x500 PNG
- [ ] Phone screenshots: Min 2, Max 8 (320-3840px)
- [ ] Tablet screenshots (if supporting tablets): 7" and 10"

Optional:
- [ ] Promo video (YouTube/Vimeo URL)
- [ ] TV banner: 1280x720

#### Privacy & Compliance
- [ ] Privacy Policy URL (required for apps with sensitive permissions)
- [ ] Data safety form:
  - Location data collection
  - Camera/Video usage
  - Network data transmission
- [ ] Target audience and content settings
- [ ] Ads declaration (if applicable)

#### Pricing & Distribution
- [ ] Select countries for distribution
- [ ] Set as Free or Paid
- [ ] Content guidelines acknowledgment

### 5. Pre-Launch Testing
- [ ] Upload to Internal Testing track first
- [ ] Add internal testers
- [ ] Gather feedback and fix issues
- [ ] Move to Closed/Open Beta if needed

### 6. Production Release
- [ ] Set rollout percentage (start with 10-20%)
- [ ] Monitor crash rates and user feedback
- [ ] Gradually increase rollout to 100%

## 🔒 Security Reminders

1. **Never commit to git**:
   - signing.properties
   - *.keystore or *.jks files
   - google-services.json (already in repo - consider removing)

2. **Backup securely**:
   - Keystore file (multiple locations)
   - Signing passwords
   - Google Play Console access

## 📱 Post-Release Monitoring

- [ ] Monitor crash reports in Firebase Crashlytics
- [ ] Check Play Console statistics
- [ ] Respond to user reviews
- [ ] Plan regular updates (Google prefers actively maintained apps)

## 🚀 Version Update Process

When releasing updates:
1. Increment versionCode (must be higher than previous)
2. Update versionName (user-visible version)
3. Use the SAME keystore and passwords
4. Test thoroughly before release
5. Consider staged rollouts

## 📞 Support Information

Add to your Play Store listing:
- Support email
- Website (if available)
- Privacy policy link
- Terms of service (if applicable)

---

**Note**: This app collects sensitive data (location, screen recordings, camera). Ensure you have:
1. Proper user consent mechanisms
2. Clear privacy policy
3. Secure data handling
4. HIPAA compliance if medical research related